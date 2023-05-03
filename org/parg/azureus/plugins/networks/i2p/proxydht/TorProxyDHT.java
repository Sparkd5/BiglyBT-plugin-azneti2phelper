/*
 * Copyright (C) Bigly Software, Inc, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307  USA
 */

package org.parg.azureus.plugins.networks.i2p.proxydht;

import java.net.InetSocketAddress;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.parg.azureus.plugins.networks.i2p.I2PHelperAltNetHandlerTor;
import org.parg.azureus.plugins.networks.i2p.I2PHelperPlugin;

import com.biglybt.core.dht.control.DHTControl;
import com.biglybt.core.dht.transport.DHTTransportAlternativeContact;
import com.biglybt.core.dht.transport.DHTTransportAlternativeNetwork;
import com.biglybt.core.dht.transport.udp.impl.DHTUDPUtils;
import com.biglybt.core.networkmanager.NetworkManager;
import com.biglybt.core.util.AENetworkClassifier;
import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.AEThread2;
import com.biglybt.core.util.AddressUtils;
import com.biglybt.core.util.BDecoder;
import com.biglybt.core.util.BEncoder;
import com.biglybt.core.util.ByteArrayHashMap;
import com.biglybt.core.util.ByteFormatter;
import com.biglybt.core.util.Constants;
import com.biglybt.core.util.CopyOnWriteList;
import com.biglybt.core.util.Debug;
import com.biglybt.core.util.RandomUtils;
import com.biglybt.core.util.SimpleTimer;
import com.biglybt.core.util.SystemTime;
import com.biglybt.core.util.TimerEvent;
import com.biglybt.core.util.TimerEventPerformer;
import com.biglybt.pif.PluginAdapter;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.ddb.DistributedDatabase;
import com.biglybt.pif.messaging.MessageException;
import com.biglybt.pif.messaging.MessageManager;
import com.biglybt.pif.messaging.generic.GenericMessageConnection;
import com.biglybt.pif.messaging.generic.GenericMessageConnectionListener;
import com.biglybt.pif.messaging.generic.GenericMessageEndpoint;
import com.biglybt.pif.messaging.generic.GenericMessageHandler;
import com.biglybt.pif.messaging.generic.GenericMessageRegistration;
import com.biglybt.pif.messaging.generic.GenericMessageConnection.GenericMessageConnectionPropertyHandler;
import com.biglybt.pif.network.ConnectionManager;
import com.biglybt.pif.network.RateLimiter;
import com.biglybt.pif.utils.PooledByteBuffer;
import com.biglybt.plugin.dht.DHTPluginContact;
import com.biglybt.plugin.dht.DHTPluginInterface;
import com.biglybt.plugin.dht.DHTPluginOperationAdapter;
import com.biglybt.plugin.dht.DHTPluginValue;

import net.i2p.data.Base32;


public class 
TorProxyDHT
{
	private static final boolean	LOOPBACK			= false;
	private static final boolean	ENABLE_LOGGING		= false;
	
	private static final boolean	ENABLE_PROXY_CLIENT = true;
	private static final boolean	ENABLE_PROXY_SERVER = true;
		
	private static final int		MIN_SERVER_VERSION	= 2;
	
	private static final int MT_PROXY_KEEP_ALIVE		= 0;
	private static final int MT_PROXY_ALLOC_REQUEST		= 1;
	private static final int MT_PROXY_ALLOC_OK_REPLY	= 2;
	private static final int MT_PROXY_ALLOC_FAIL_REPLY	= 3;
	private static final int MT_PROXY_PROBE_REQUEST		= 4;
	private static final int MT_PROXY_PROBE_REPLY		= 5;
	private static final int MT_PROXY_CLOSE				= 6;
	private static final int MT_PROXY_OP_REQUEST		= 7;
	private static final int MT_PROXY_OP_REPLY			= 8;

	private static final int PROXY_OP_PUT		= 1;
	private static final int PROXY_OP_GET		= 2;
	private static final int PROXY_OP_REMOVE	= 3;
	
	private static final int MAX_SERVER_PROXIES	= 4;
	
	private static final int MAX_KEY_STATE			= 256;

	private final I2PHelperPlugin		plugin;
	private final PluginInterface		plugin_interface;
	
	private volatile boolean closing_down;
	
	private DistributedDatabase	ddb ;

	private final String instance_id;
	
	private final RateLimiter inbound_limiter;
	
	private I2PHelperPlugin.TorEndpoint tep_mix;
	private I2PHelperPlugin.TorEndpoint tep_pure;

	private GenericMessageRegistration msg_registration;
	
	private CopyOnWriteList<Connection>		connections = new CopyOnWriteList<>();

	private volatile OutboundConnectionProxy	current_client_proxy;
	
	private CopyOnWriteList<InboundConnectionProxy>	server_proxies = new CopyOnWriteList<>();
	
	private boolean	checking_client_proxy;
	private boolean client_proxy_check_outstanding;

	private InetSocketAddress failed_client_proxy;
	
	private int failed_client_proxy_retry_count = 0;
	
	private int PROXY_FAIL_MAX	= 256;
	
	private volatile int proxy_client_consec_fail_count;
	
	private Map<String,String>		proxy_client_fail_map = 
		new LinkedHashMap<String,String>(PROXY_FAIL_MAX,0.75f,true)
		{
			@Override
			protected boolean
			removeEldestEntry(
		   		Map.Entry<String,String> eldest) 
			{
				return size() > PROXY_FAIL_MAX;
			}
		};
		
	private int PROXY_BACKUP_MAX	= 64;
		
	private Map<InetSocketAddress,String>		proxy_client_backup_map = 
		new LinkedHashMap<InetSocketAddress,String>(PROXY_BACKUP_MAX,0.75f,true)
		{
			@Override
			protected boolean
			removeEldestEntry(
		   		Map.Entry<InetSocketAddress,String> eldest) 
			{
				return size() > PROXY_BACKUP_MAX;
			}
		};

	private AtomicLong						proxy_request_seq	= new AtomicLong();
	private LinkedList<ProxyLocalRequest>	proxy_requests		= new LinkedList<>();
	private AESemaphore						proxy_requests_sem	= new AESemaphore( "TPD:req" );
	private AEThread2						proxy_request_dispatcher;
	
	private OutboundConnectionProxy			active_client_proxy;
	
	private int PROXY_FAIL_UIDS_MAX	= 8;
	
	private Map<String,String>		proxy_client_fail_uids = 
		new LinkedHashMap<String,String>(PROXY_FAIL_UIDS_MAX,0.75f,true)
		{
			@Override
			protected boolean
			removeEldestEntry(
		   		Map.Entry<String,String> eldest) 
			{
				return size() > PROXY_FAIL_UIDS_MAX;
			}
		};
		
	private volatile boolean destroyed;
	
	public
	TorProxyDHT(
		I2PHelperPlugin	_plugin )
	{
		plugin = _plugin;
		
		plugin_interface = plugin.getPluginInterface();
		
		byte[] temp = new byte[8];
		
		RandomUtils.nextSecureBytes( temp );
		
		instance_id = Base32.encode( temp );
		
		ConnectionManager cman = plugin_interface.getConnectionManager();

		int inbound_limit = 50*1024;

		inbound_limiter 	= cman.createRateLimiter( "TorDHTProxy:in", inbound_limit );
				
		plugin_interface.addListener(
			new PluginAdapter()
			{
				@Override
				public void 
				closedownInitiated()
				{
					closing_down = true;
					
					for ( Connection con: connections ){
						
						con.closingDown();
					}
				}
			});
	}
	
	public void
	initialise()
	{		
		ddb = plugin_interface. getDistributedDatabase();

		tep_mix		= plugin.getTorEndpoint( 0 );
		tep_pure	= plugin.getTorEndpoint( 1 );
		
		try{
			int mix_port = tep_mix.getPort();
			
			msg_registration =
					plugin_interface.getMessageManager().registerGenericMessageType(
						"TorProxyDHT",
						"TorProxyDHT Registration",
						MessageManager.STREAM_ENCRYPTION_NONE,
						new GenericMessageHandler()
						{
							@Override
							public boolean
							accept(
								GenericMessageConnection	gmc )
	
								throws MessageException
							{
								InetSocketAddress originator = gmc.getEndpoint().getNotionalAddress();
								
								if ( AENetworkClassifier.categoriseAddress( AddressUtils.getHostAddress( originator)) != AENetworkClassifier.AT_TOR ){
									
									gmc.close();
									
									return( false );	
								}							
								
								if ( originator.getPort() == mix_port ){
									
									new InboundConnectionProxy( gmc );
									
								}else{
									
									new InboundConnectionProbe( gmc );
								}
								
								return( true );
							}
						});
				
				final int TIMER_PERIOD = 10*1000;
				
				final int REPUBLISH_CHECK_PERIOD	= 5*60*1000;
				final int REPUBLISH_CHECK_TICKS		= REPUBLISH_CHECK_PERIOD / TIMER_PERIOD;
						
				SimpleTimer.addPeriodicEvent(
					"TorProxyDHT",
					TIMER_PERIOD,
					new TimerEventPerformer()
					{
						int tick_count = -1;
						
						@Override
						public void 
						perform(
							TimerEvent event)
						{
							tick_count++;
							
							checkRequestTimeouts();
							
							long now = SystemTime.getMonotonousTime();

							if ( ENABLE_LOGGING ){
								
								printLocalKeyState();
							
								if ( tick_count % 6 == 0 ){
									
									OutboundConnectionProxy ccp = current_client_proxy;
									
									String ccp_str;
									
									if ( ccp == null ){
										
										ccp_str = "";
										
									}else{
										
										ccp_str = ", " + ccp.getString();
									}
									
									log( "con=" + connections.size() + ccp_str );
									
									for ( InboundConnection sp: server_proxies ){
										
										log( "    " + sp.getString());
									}
								}
							}
							
							checkClientProxy( true );
							
							if ( tick_count % 3 == 0 ){
								
								checkServerProxies();
								
								for ( Connection con: connections ){
										
									con.timerTick( now );
									
									if ( 	con != current_client_proxy && 
											!( con instanceof InboundConnectionProxy && server_proxies.contains((InboundConnectionProxy)con ))){
										
										if ( con.getAgeSeconds( now ) > 120 ){
											
											con.failed( new Exception( "Dead connection" ));
										}
									}
								}
							}
							
							if ( tick_count % REPUBLISH_CHECK_TICKS == 0 ){
								
								checkRepublish();
							}
						}
					});
				
			checkClientProxy( true );
			
			byte[] torrent_hash = new byte[]{ 0,1,2,3,4,5 };
				
			proxyTrackerAnnounce( torrent_hash, true );
			
		}catch( Throwable e ){
			
			Debug.out( e );
		}
	}
	
	public void
	proxyTrackerAnnounce(
		byte[]		torrent_hash,
		boolean		is_seed )
	{
		try{
			MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
						
			sha256.update( "TorProxyDHT::torrent_hash".getBytes( Constants.UTF_8 ));
			
			sha256.update( torrent_hash );
			
			byte[] key = sha256.digest();
				
			Map payload = new HashMap<>();
			
			payload.put( "s", is_seed?1:0 );
			
			if ( NetworkManager.REQUIRE_CRYPTO_HANDSHAKE ){
				
				payload.put( "c", 1 );
			}
			
			Map	options = new HashMap<>();
			
			options.put( "f", is_seed?DHTPluginInterface.FLAG_SEEDING:DHTPluginInterface.FLAG_DOWNLOADING );
			
			proxyLocalPut( key, payload, options );
			
		}catch( Throwable e ){
			
			Debug.out( e );
		}
	}
	
	public void
	proxyTrackerGet(
		byte[]					torrent_hash,
		boolean					is_seed,
		int						num_want,
		TorProxyDHTListener		listener )
	{
		try{
			MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
			
			sha256.update( "TorProxyDHT::torrent_hash".getBytes( Constants.UTF_8 ));
			
			sha256.update( torrent_hash );
			
			byte[] key = sha256.digest();
	
			long timeout = 2*60*1000;
			
			Map	options = new HashMap<>();
			
			options.put( "f", is_seed?DHTPluginInterface.FLAG_SEEDING:DHTPluginInterface.FLAG_DOWNLOADING );
			options.put( "t", timeout );
			options.put( "n", num_want );
	
			proxyLocalGet( 
				key, 
				options, 
				timeout,
				new ProxyGetListener()
				{	
					@Override
					public void 
					valuesRead(
						byte[]				key,
						List<Map> 			values )
					{
						for ( Map v: values ){
							
							try{
								String	host = (String)v.get( "h" );
								int		port = ((Number)v.get( "p" )).intValue();
							
								boolean is_seed = ((Number)v.get( "s" )).intValue() != 0;
								
								Number c = (Number)v.get( "c" );
								
								boolean require_crypto = false;
								
								if ( c != null ){
									
									require_crypto = c.intValue() == 1;
								}
								
								listener.proxyValueRead(
									InetSocketAddress.createUnresolved(host, port),
									is_seed,
									require_crypto );
								
							}catch( Throwable e ){
								
								Debug.out( e );
							}
						}
					}
					
					@Override
					public void 
					complete(
						byte[]			 	key, 
						boolean 			timeout )
					{
						try{
							listener.proxyComplete( torrent_hash, timeout );
							
						}catch( Throwable e ){
							
							Debug.out( e );
						}
					}
				});
			
		}catch( Throwable e ){
			
			Debug.out( e );
			
			listener.proxyComplete( torrent_hash, true );
		}
	}
	
	public void
	proxyTrackerRemove(
		byte[]		torrent_hash )
	{
		try{
			MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
			
			sha256.update( "TorProxyDHT::torrent_hash".getBytes( Constants.UTF_8 ));
			
			sha256.update( torrent_hash );
			
			byte[] key = sha256.digest();
			
			proxyLocalRemove( key );
			
		}catch( Throwable e ){
			
			Debug.out( e );
		}
	}
	
	public void
	proxyPut(
		byte[]			key,
		byte[]			value,
		Map				options )
	
		throws Exception
	{
		Map map = new HashMap<>();
		
		map.put( "v", value );
		
		proxyLocalPut( key, map, options );
	}

	public void
	proxyGet(
		byte[]			key )
	{
		proxyLocalGet(
			key, null, 2*60*1000,
			new ProxyGetListener(){
				
				@Override
				public void 
				valuesRead(
					byte[]		key, 
					List<Map>	values)
				{
					log( "proxyGet valuesRead: values=" + values.size());
					
					for ( Map m: values ){
						
						log( "    " + BDecoder.decodeStrings(m));
					}
				}
				
				@Override
				public void 
				complete(
					byte[] key, 
					boolean timeout)
				{
					log( "proxyGet complete, timeout=" + timeout );
				}
			});
	}
	
	public void
	proxyRemove(
		byte[]			key )
	
		throws Exception
	{		
		proxyLocalRemove( key );
	}
	
		// local operations
	
	
	private void
	proxyLocalPut(
		byte[]		key,
		Map			value,
		Map			options )
	
		throws Exception
	{
			// "h", "p" and "z" are reserved
		
		if ( value.containsKey("h") || value.containsKey("p")|| value.containsKey( "z" )){
			
			throw( new Exception( "Invalid map, uses reserved keys" ));
		}
		
		ProxyLocalRequestPut request = new ProxyLocalRequestPut( key, value );
		
		request.setOptions( options );
		
		addRequest( request );
	}
	
	private void
	proxyLocalGet(
		byte[]					key,
		Map						options,
		long					timeout,
		ProxyGetListener		listener )
	{
		ProxyLocalRequestGet	request = new ProxyLocalRequestGet( key );
		
		request.setOptions( options );
		
		request.setListener( listener );
		
		request.setTimeout( timeout );
		
		addRequest( request );		
	}
	
	private void
	proxyLocalRemove(
		byte[]		key )
	{
		addRequest( new ProxyLocalRequestRemove( key ));		
	}
	
	class
	LocalKeyState
	{
		private ProxyLocalRequest	pending_request;
		private boolean			has_active_request;
		
		private long			last_ok_time;
		private ProxyLocalRequest	last_ok_request;
		private String			last_ok_proxy_iid;
	}
	
	private ByteArrayHashMap<LocalKeyState>	local_key_state = new ByteArrayHashMap<>();
	
	private void
	printLocalKeyState()
	{
		synchronized( proxy_requests ){
			
			log( "LKS size=" + local_key_state.size());
			
			for ( byte[] key: local_key_state.keys()){
				
				LocalKeyState lks = local_key_state.get( key );
				
				String str = "pend=" + lks.pending_request + ", act=" + lks.has_active_request + 
						", lok=" + lks.last_ok_request + ", liid=" + lks.last_ok_proxy_iid;
						
				log( "    " + ByteFormatter.encodeString(key,0,Math.min(key.length,8)) + " -> " + str );
			}
		}
	}
	
	private void
	addRequest(
		ProxyLocalRequest		request )
	{
		synchronized( proxy_requests ){
			
			boolean queue_request = false;
			
			int request_type = request.getType();
			
			if ( request_type == ProxyLocalRequest.RT_GET ){
				
					// get requests all get queued and will timeout as necessary
				
				queue_request = true;
				
			}else{
				
					// non-get requests get their local state tracked, there will only ever
					// be at most one request queued
				
				byte[] key = request.getKey();
				
				LocalKeyState	lks = local_key_state.get( key );
				
				if ( lks != null ){
					
					if ( lks.pending_request != null ){
					
						proxy_requests.remove( lks.pending_request );
					}
					
				}else{
					
					if ( local_key_state.size() >= MAX_KEY_STATE && request_type == ProxyLocalRequest.RT_PUT ){
						
						Debug.out( "Key state size exceeded" );
						
						return;
					}
					
					lks = new LocalKeyState();
										
					local_key_state.put( key, lks );
				}
				
				lks.pending_request = request;
				
				if ( !lks.has_active_request ){
					
					if ( request_type == ProxyLocalRequest.RT_REMOVE ){
						
						local_key_state.remove( key );
						
					}else{
					
						queue_request = true;
					}
				}
			}

			if ( queue_request ){
				
				proxy_requests.add( request );
				
				proxy_requests_sem.release();
			
				checkRequestDispatcher();
			}
		}
	}
	
	private void
	requestComplete(
		OutboundConnectionProxy		proxy,
		ProxyLocalRequest				request )
	{
		log( "request complete:" + request );
		
		if ( request.getType() == ProxyLocalRequest.RT_GET ){
			
			((ProxyLocalRequestGet)request).setComplete();
			
		}else{
			
			synchronized( proxy_requests ){
				
				byte[] key = request.getKey();
				
				LocalKeyState	lks = local_key_state.get( key );
				
				if ( lks == null ){
					
					Debug.out( "lks shouldn't be null" );
					
				}else{
					
					if ( !lks.has_active_request ){
						
						Debug.out( "lks should have active request" );
					}
					
					lks.has_active_request = false;
					
					lks.last_ok_time		= SystemTime.getMonotonousTime();
					lks.last_ok_request 	= request;
					lks.last_ok_proxy_iid	= proxy.getRemoteInstanceID();
					
					if ( lks.pending_request != null ){
						
						proxy_requests.add( lks.pending_request );
						
						proxy_requests_sem.release();
					
						checkRequestDispatcher();
						
					}else{
						
						if ( request.getType() == ProxyLocalRequest.RT_REMOVE ){
							
							local_key_state.remove( key );
						}
					}
				}
			}
		}
	}
	
	private void
	requestFailed(
		ProxyLocalRequest				request )
	{
		log( "request failed:" + request );
		
		if ( request.getType() == ProxyLocalRequest.RT_GET ){
			
			((ProxyLocalRequestGet)request).setFailed();
			
		}else{
			
			synchronized( proxy_requests ){
				
				byte[] key = request.getKey();
				
				LocalKeyState	lks = local_key_state.get( key );
				
				if ( lks == null ){
					
					Debug.out( "lks shouldn't be null" );
					
				}else{
					
					if ( !lks.has_active_request ){
						
						Debug.out( "lks should have active request" );
					}
					
					lks.has_active_request = false;
										
					if ( lks.pending_request == null ){
						
						lks.pending_request = request;
					}
											
					proxy_requests.add( lks.pending_request );
						
					proxy_requests_sem.release();
					
					checkRequestDispatcher();
				}
			}
		}
	}
	
	private void
	checkRepublish()
	{
		synchronized( proxy_requests ){
	
			if ( active_client_proxy == null ){
				
				return;
			}
			
			String iid = active_client_proxy.getRemoteInstanceID();
			
			if ( iid == null ){
				
				return;
			}
				
			long now = SystemTime.getMonotonousTime();
			
			boolean added = false;
			
			for ( byte[] key: local_key_state.keys()){
				
				LocalKeyState lks = local_key_state.get( key );
				
				if ( lks.pending_request == null && !lks.has_active_request && lks.last_ok_time > 0){
					
					long elapsed = now - lks.last_ok_time;
					
					if ( elapsed > DHTControl.ORIGINAL_REPUBLISH_INTERVAL_DEFAULT ){
						
						if ( !iid.equals( lks.last_ok_proxy_iid )){
							
							lks.pending_request = lks.last_ok_request;
							
							proxy_requests.add( lks.pending_request );
							
							proxy_requests_sem.release();
							
							added = true;
						}
					}
				}
			}
			
			if ( added ){
				
				checkRequestDispatcher();
			}
		}
	}
	
	private void
	checkRequestTimeouts()
	{
		long now = SystemTime.getMonotonousTime();
		
		OutboundConnectionProxy	proxy;
		
		List<ProxyLocalRequest>	failed = new ArrayList<>();
		
		synchronized( proxy_requests ){
			
			proxy = active_client_proxy;
			
			Iterator<ProxyLocalRequest>	it = proxy_requests.iterator();
			
			while( it.hasNext()){
				
				ProxyLocalRequest request = it.next();
				
				if ( request.getType() == ProxyLocalRequest.RT_GET ){
					
					ProxyLocalRequestGet get = (ProxyLocalRequestGet)request;
					
						// non-active requests get a short timeout as there is no usable
						// proxy
					
					long timeout = Math.min( get.getTimeout(), 15*1000 );
					
					if ( now - get.getStartTime() > timeout ){
						
						failed.add( request );
						
						it.remove();
					}
				}
			}
		}
		
		for ( ProxyLocalRequest r: failed ){
			
			requestFailed( r );
		}
		
		if ( proxy != null ){
			
			proxy.checkRequestTimeouts();
		}
	}
	
	private void
	checkRequestDispatcher()
	{
		if ( proxy_request_dispatcher == null ){
			
			if ( active_client_proxy != null ){
				
				proxy_request_dispatcher = 
					new AEThread2( "TPD:rd" )
					{
						public void 
						run()
						{
							while( true ){
								
								if ( !proxy_requests_sem.reserve( 10*1000)){
									
									synchronized( proxy_requests ){
										
										if ( proxy_requests.isEmpty()){
											
											if ( proxy_request_dispatcher == this ){
												
												proxy_request_dispatcher = null;
												
												return;
											}
										}
									}
									
									continue;
								}
								
								OutboundConnectionProxy	proxy;
								
								ProxyLocalRequest 			request;
								
								synchronized( proxy_requests ){
									
									proxy = active_client_proxy;

									if (	proxy == null ||
											proxy.getState() != OutboundConnectionProxy.STATE_ACTIVE ||
											proxy_request_dispatcher != this ){
										
										proxy_requests_sem.release();
										
										break;
									}
																		
									if ( proxy_requests.isEmpty()){
										
										request = null;
										
									}else{
										
										request = proxy_requests.removeLast();
										
										if ( request.getType() != ProxyLocalRequest.RT_GET ){
											
											byte[] key = request.getKey();
											
											LocalKeyState	lks = local_key_state.get( key );
											
											if ( lks == null ){
												
												Debug.out( "lks should always exist" );
												
											}else{
												
												lks.pending_request = null;
												
												if ( lks.has_active_request ){
													
													Debug.out( "lks shouldn't have an active request" );
													
												}else{
													
													lks.has_active_request = true;
												}
											}
										}
									}
								}
								
								if ( request != null ){
									
									proxy.addRequest( 
										request,
										new ActiveRequestListener()
										{
											private AtomicBoolean done = new AtomicBoolean();
											
											@Override
											public void 
											complete(
												OutboundConnectionProxy		proxy,
												ProxyLocalRequest				request )
											{
												if ( !done.compareAndSet( false, true )){
													
													return;
												}
												
												requestComplete( proxy, request );
											}
											
											@Override
											public void 
											failed(
												OutboundConnectionProxy		proxy,
												ProxyLocalRequest				request )
											{
												if ( !done.compareAndSet( false, true )){
													
													return;
												}
												
												requestFailed( request );
											}
										});
								}
							}
						}
					};
				
				proxy_request_dispatcher.start();
			}
		}else{
			
			if ( active_client_proxy == null ){
				
				proxy_requests_sem.release();
				
				proxy_request_dispatcher = null;
			}
		}
	}
	
		

	
	private void
	addBackupContacts(
		List<InetSocketAddress>		isas )
	{
		List<InetSocketAddress>		to_add = new ArrayList<>();
		
		synchronized( proxy_client_fail_map ){
		
			for ( InetSocketAddress isa: isas ){
				
				if ( !proxy_client_fail_map.containsKey( AddressUtils.getHostAddress(isa))){
					
					to_add.add( isa );
				}
			}
		}
		
		synchronized( proxy_client_backup_map ){
			
			for ( InetSocketAddress isa: to_add ){
				
				proxy_client_backup_map.put( isa, "" );
			}
		}
	}
		
	private void
	checkClientProxy(
		boolean 	force )
	{
		if ( destroyed || !ENABLE_PROXY_CLIENT ){
			
			return;
		}
		
		if ( proxy_client_consec_fail_count > 10 && !force ){
			
				// fall back to periodic attempts rather than immediate retries
			
			return;
		}
		
		synchronized( connections ){
			
			if ( current_client_proxy != null && !current_client_proxy.isClosed()){
				
				return;
			}
			
			if ( checking_client_proxy ){
				
				client_proxy_check_outstanding = true;
				
				return;
			}
			
			checking_client_proxy = true;
		}
		
		AEThread2.createAndStartDaemon( "ProxyClientCheck", ()->{
										
				try{
					checkClientProxySupport();
					
				}finally{
					
					boolean recheck = false;
					
					synchronized( connections ){
						
						checking_client_proxy = false;
						
						if ( client_proxy_check_outstanding ){
							
							client_proxy_check_outstanding = false;
							
							recheck = true;
						}
					}
					
					if ( recheck ){
						
						checkClientProxy( false );
					}
				}
		});
	}
	
	private void
	checkServerProxies()
	{
		if ( destroyed ){
			
			return;
		}
		
			// just in case
		
		for ( InboundConnectionProxy sp: server_proxies ){
			
			if ( sp.isClosed()){
				
				server_proxies.remove( sp);
				
			}else{
				
				sp.checkRequests();
			}
		}
	}
	
	private boolean
	checkClientProxySupport()
	{
		if ( destroyed || closing_down ){
			
			return( false );
		}
		
		String local_mix_host	= tep_mix.getHost();
		String local_pure_host	= tep_pure.getHost();
		
		if ( local_mix_host == null || local_pure_host == null ){
			
			return( false );
		}
		
		if ( !ddb.isInitialized()){
			
			return( false );
		}
		
		InetSocketAddress retry_address = null;
		
		synchronized( proxy_client_fail_map ){
		
			if ( failed_client_proxy != null ){
				
				if ( failed_client_proxy_retry_count < 3 ){
					
					retry_address = failed_client_proxy;
					
					proxy_client_fail_map.remove( AddressUtils.getHostAddress( retry_address ));

					failed_client_proxy_retry_count++;
					
				}else{
					
					failed_client_proxy				= null;
					failed_client_proxy_retry_count	= 0;
				}
			}
		}
		
		if ( retry_address != null ){
			
			log( "Retrying client proxy connection" );
			
			if ( tryClientProxy( local_mix_host, retry_address )){
				
				return( true );
			}
		}
		
		DHTTransportAlternativeNetwork net = DHTUDPUtils.getAlternativeNetwork( DHTTransportAlternativeNetwork.AT_TOR );
		
		if ( net == null ){
			
			return( false );
		}
		
		List<DHTTransportAlternativeContact> contacts = DHTUDPUtils.getAlternativeContacts( DHTTransportAlternativeNetwork.AT_TOR, 128 );

		Collections.shuffle( contacts );
		
		for ( DHTTransportAlternativeContact contact: contacts ){
		
			InetSocketAddress target = net.getNotionalAddress( contact );
			
			if ( target == null ){
				
				continue;
			}

			int contact_version = contact.getVersion();
			
			if ( contact_version == 0 || contact_version >= MIN_SERVER_VERSION || LOOPBACK ){
			
				if ( tryClientProxy( local_mix_host, target )){
				
					return( true );
				}
			}
		}
		
		List<InetSocketAddress> backups = null;
		
		synchronized( proxy_client_backup_map ){
			
			if ( !proxy_client_backup_map.isEmpty()){
			
				backups = new ArrayList<>( proxy_client_backup_map.keySet());
				
				proxy_client_backup_map.clear();
			}
		}
		
		if ( backups != null ){
			
			Iterator<InetSocketAddress> it = backups.iterator();
			
			while( it.hasNext()){
				
				InetSocketAddress target = it.next();
				
				it.remove();
				
				if ( tryClientProxy( local_mix_host, target )){
					
					synchronized( proxy_client_backup_map ){
						
						while( it.hasNext()){
							
							proxy_client_backup_map.put( it.next(), "" );
						}
					}
					
					return( true );
				}
			}
		}
		
			// nothing found, reset
		
		synchronized( proxy_client_fail_map ){
			
			proxy_client_fail_map.clear();
		}
		
		return( false );
	}
	
	boolean
	tryClientProxy(
		String				local_mix_host,
		InetSocketAddress	target )
	{
		String target_host = AddressUtils.getHostAddress(target);
		
		if ( AENetworkClassifier.categoriseAddress( target_host ) != AENetworkClassifier.AT_TOR ){
			
			return( false );
		}
		
		if ( local_mix_host.equals( target_host )){
							
			return( false );
		}
		
		synchronized( proxy_client_fail_map ){
			
			if ( proxy_client_fail_map.containsKey( target_host )){
				
				return( false );
			}
			
				// preemptively add it, we'll remove it if it succeeds
			
			proxy_client_consec_fail_count++;
			
			proxy_client_fail_map.put( target_host, "" );
		}
		
		try{	
			if ( LOOPBACK ){
			
				target = InetSocketAddress.createUnresolved( "umklbodffjt4jhjm7ysfoej3nctmtm7yatvbopq2lo32x45am3siddqd.onion", 27657);
				// target = InetSocketAddress.createUnresolved( "nhrtp6h2o7puwq5ce45f3dfvszal3jeq4d5b3lgjxi72k2x5pspz5mad.onion", 27657);
			}
			
			log( "Trying proxy " + target );

			new OutboundConnectionProxy( target );
			
			return( true );
			
		}catch( Throwable e ){
			
		}
		
		return( false );
	}
	
	private void
	proxyClientSetupComplete(
		OutboundConnectionProxy		proxy,
		String						instance_id )
	{
		synchronized( proxy_client_fail_map ){
			
			proxy_client_consec_fail_count = 0;
			
			proxy_client_fail_map.remove( proxy.getHost());
			
			failed_client_proxy = null;
			
			failed_client_proxy_retry_count = 0;
		}
		
		synchronized( proxy_requests ){
		
			if ( !proxy_client_fail_uids.containsKey( proxy.getLocalUID())){
				
				active_client_proxy = proxy;
				
				checkRequestDispatcher();
			}
		}
	}
	
	private void
	proxyClientFailed(
		OutboundConnectionProxy		proxy )
	{
		synchronized( proxy_requests ){
	
			proxy_client_fail_uids.put( proxy.getLocalUID(), "" );
			
			if ( active_client_proxy == proxy ){
				
				active_client_proxy = null;
			}
			
			checkRequestDispatcher();
		}
	}
	
	private void
	proxyServerSetupComplete(
		InboundConnectionProxy	proxy,
		String					instance_id )
	{
		
	}
	
	private void
	proxyServerFailed(
		InboundConnectionProxy	proxy )
	{
		
	}
	
	private void
	addConnection(
		Connection		connection )
	{
		OutboundConnectionProxy old_proxy = null;
		
		synchronized( connections ){
		
			connections.add( connection );
			
			if ( connection instanceof OutboundConnectionProxy ){
				
				if ( current_client_proxy != null && current_client_proxy != connection ){
					
					old_proxy = current_client_proxy;
				}
				
				current_client_proxy = (OutboundConnectionProxy)connection;
			}
		}
		
		if ( old_proxy != null ){
			
			old_proxy.close();
		}
	}
	
	private void
	removeConnection(
		Connection		connection )
	{
		boolean was_client_proxy;
		
		boolean was_server_proxy;
		
		synchronized( connections ){
		
			connections.remove( connection );
			
			was_client_proxy = connection == current_client_proxy;
			
			if ( was_client_proxy ){
				
				current_client_proxy = null;
			}
			
			if ( connection instanceof InboundConnectionProxy ){
			
				was_server_proxy = server_proxies.remove((InboundConnectionProxy)connection );
				
			}else{
				
				was_server_proxy = false;
			}
		}
		
		if ( was_client_proxy ){
			
			OutboundConnectionProxy cp = (OutboundConnectionProxy)connection;
			
			proxyClientFailed( cp );
			
			InetSocketAddress ias = cp.getAddress();
			
			if ( cp.hasBeenActive()){
				
				synchronized( proxy_client_fail_map ){
					
					if ( failed_client_proxy == null || !failed_client_proxy.equals( ias )){
						
						failed_client_proxy = ias;
						
						failed_client_proxy_retry_count = 0;
					}
				}
				
				checkClientProxy( true );
				
			}else{
				
				checkClientProxy( false );
			}
		}
		
		if ( was_server_proxy ){
			
			proxyServerFailed(( InboundConnectionProxy) connection );
		}
	}
	
	
	private void
	maskValue(
		byte[]		torrent_hash,
		byte[]		masked_value )
	
		throws Exception
	{
		int	pos = 0;
		
		MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
		
		for ( int i=0;pos<masked_value.length;i++){
			
			sha256.update( "TorProxyDHT::mask".getBytes( Constants.UTF_8 ));
			
			sha256.update((byte)i);
			
			sha256.update( torrent_hash );
			
			byte[] value_mask = sha256.digest();
			
			for ( int m=0;pos<masked_value.length&&m<value_mask.length;pos++,m++){
				
				masked_value[pos] ^= value_mask[m];
			}
		}
	}

	public void
	destroy()
	{
		destroyed = true;
		
		if ( msg_registration != null ){
			
			msg_registration.cancel();
			
			msg_registration = null;
		}
		
		for ( Connection con: connections ){
			
			con.close();
		}
	}
	
	private void
	log(
		String		str )
	{
		if ( ENABLE_LOGGING ){
		
			System.out.println( str );
		}
	}
	
	private abstract class
	Connection
		implements GenericMessageConnectionListener, GenericMessageConnectionPropertyHandler
	{
		private final long start_time = SystemTime.getMonotonousTime();
		
		private GenericMessageConnection	gmc;
		
		private long	last_received_time	= start_time;
		private long	last_sent_time		= start_time;
				
		private long	disconnect_after	= -1;
		
		private volatile boolean	connected;
		
		private volatile boolean failed;
		
		Connection()
		{
			log( getName() + ": created" );
		}
		
		protected void
		setConnection(
			GenericMessageConnection	_gmc )
		{
			gmc = _gmc;
			
			addConnection( this );
					
			gmc.addInboundRateLimiter( inbound_limiter );
			
			gmc.addListener( this );
		}

		protected void
		setConnected()
		{
			if ( this instanceof OutboundConnection ){
			
				log( getName() + ": connected (" + ( SystemTime.getMonotonousTime() - start_time )/1000 + "s)");
			}
			
			connected	= true;
		}
		
		private void
		timerTick(
			long	now )
		{
			if ( disconnect_after >= 0 && now > disconnect_after ){
				
				failed( new Exception( "Force disconnect" ));
				
				return;
			}
			
			if ( !connected ){
				
				return;
			}
			
			if ( now - last_received_time > 120*1000 ){
				
				failed( new Exception( "Inactivity timeout" ));
				
			}else if ( now - last_sent_time >= 60*1000 ){
				
				Map map = new HashMap<>();
								
				send( MT_PROXY_KEEP_ALIVE, map );
			}
		}
		
		protected int
		getAgeSeconds(
			long		now )
		{
			return((int)((now - start_time)/1000));
		}
		
		protected void
		setDisconnectAfterSeconds(
			int		secs )
		{
			if ( secs < 0 ){
				
				disconnect_after = -1;
				
			}else{
				
				disconnect_after = SystemTime.getMonotonousTime() + secs*1000;
			}
		}
		
		protected void
		send(
			int		type,
			Map		map )
		{
			last_sent_time	= SystemTime.getMonotonousTime();
			
			map.put( "type", type );
			
			map.put( "ver", I2PHelperAltNetHandlerTor.LOCAL_VERSION );

			if ( type != MT_PROXY_KEEP_ALIVE ){
				
				log( "send " + map );
			}
			
			PooledByteBuffer buffer = null;
			
			try{
				buffer = plugin_interface.getUtilities().allocatePooledByteBuffer( BEncoder.encode(map));
				
				gmc.send( buffer );
				
				buffer = null;
				
			}catch( Throwable e ){
								
				if ( buffer != null ){
					
					buffer.returnToPool();
				}
				
				failed( e );
			}
		}
		
		@Override
		public void 
		receive(
			GenericMessageConnection	connection, 
			PooledByteBuffer 			message )
					
			throws MessageException
		{
			last_received_time = SystemTime.getMonotonousTime();
					
			try{
				Map map = BDecoder.decode( message.toByteArray());
								
				int	type = ((Number)map.get( "type" )).intValue();
				
				if ( type == MT_PROXY_CLOSE ){
					
					disconnect_after = 0;
					
				}else if ( type != MT_PROXY_KEEP_ALIVE ){
				
					log( "receive " + map );

					receive( type, map );
				}
				
			}catch( Throwable e ){
										
				failed( e );

			}finally{
				
				message.returnToPool();
			}
		}
		
		protected abstract void
		receive(
			int		type,
			Map		map )

			throws Exception;
		
		@Override
		public void 
		failed(
			GenericMessageConnection	connection, 
			Throwable					error ) 
					
			throws MessageException
		{
			failed( error );
		}
		
		protected void
		closingDown()
		{
			if ( connected ){
			
				Map map = new HashMap<>();
				
				send( MT_PROXY_CLOSE, map );
			}
		}
		
		protected void
		close()
		{
			synchronized( this ){
				
				if ( failed ){
					
					return;
				}
				
				failed = true;
			}
			
			try{
				try{
					gmc.close();
					
				}catch( Throwable e ){
				}
								
				removeConnection( this );
				
			}finally{
				
				setClosed();
			}
		}
		
		protected void
		failed(
			Throwable 	error )
		{
			synchronized( this ){
				
				if ( failed ){
					
					return;
				}
				
				failed = true;
			}
			
			try{
				if ( disconnect_after == -1 ){
				
						// unexpected
					
					log( getName() + " failed: " + Debug.getNestedExceptionMessage(error));
				}
				
				try{
					gmc.close();
					
				}catch( Throwable e ){
				}
								
				removeConnection( this );
				
			}finally{
				
				setClosed();
			}
		}
		
		protected void
		setClosed()
		{
		}
		
		protected abstract String
		getName();
		
		protected String
		getString()
		{
			long now = SystemTime.getMonotonousTime();
			
			return( getName() + ", idle_in=" + (now - last_received_time)/1000 + "s, idle_out=" + (now - last_sent_time)/1000 + "s");
		}
		
		protected boolean
		isClosed()
		{
			return( failed );
		}
		
		@Override
		public Object 
		getConnectionProperty(
			String property_name )
		{
			return( null );
		}
	}
	
	private abstract class
	OutboundConnection
		extends Connection
	{	
		private final InetSocketAddress		target;
		
		private
		OutboundConnection(
			InetSocketAddress		_target )
		
			throws Exception
		{
			target	= _target;
			
			GenericMessageEndpoint ep = msg_registration.createEndpoint( target );
			
			ep.addTCP( target );
			
			GenericMessageConnection gmc = msg_registration.createConnection( ep );
							
			setConnection( gmc );
			
			try{
				gmc.connect( this );
				
			}catch( Throwable e ){
				
				failed( e );
			}
		}
		
		protected InetSocketAddress
		getAddress()
		{
			return( target );
		}
		
		protected String
		getHost()
		{
			return( AddressUtils.getHostAddress(target));
		}
		
		protected String
		getString()
		{
			return( super.getString() + ": " + getHost());
		}
	}
	
	private class
	OutboundConnectionProxy
		extends OutboundConnection
	{
		public static final int STATE_INITIALISING	= 0;
		public static final int STATE_ACTIVE		= 1;
		public static final int STATE_FAILED		= 2;
		
		private final String	tep_pure_host;
		private final int		tep_pure_port;
		private final byte[]	tep_pure_host_bytes;	// prefer to use pk but sha3_256 not in Java 8 ...
		
		private final String	local_uid;
		private String			remote_iid;
		
		private volatile int state	= STATE_INITIALISING;
		
		private volatile boolean	has_been_active;
		
		private Map<Long,ActiveRequest>		active_requests = new IdentityHashMap<>();
		
		private
		OutboundConnectionProxy(
			InetSocketAddress		target )
		
			throws Exception
		{
			super( target );
				
			tep_pure_host	= tep_pure.getHost();
			tep_pure_port	= tep_pure.getPort();
			
			tep_pure_host_bytes		= I2PHelperPlugin.TorEndpoint.onionToBytes( tep_pure_host );
			
			byte[] _uid = new byte[32];
			
			RandomUtils.nextSecureBytes(_uid);
						
			local_uid = Base32.encode(_uid);
		}
		
		@Override
		protected String
		getName()
		{
			return( "Proxy out" );
		}
		
		protected String
		getLocalUID()
		{
			return( local_uid );
		}
		
		protected String
		getRemoteInstanceID()
		{
			return( remote_iid );
		}
		
		protected void
		setState(
			int		_state )
		{
			synchronized( this ){
				
				if ( state == STATE_FAILED ){
					
					return;
				}
				
				state = _state;
				
				if ( state == STATE_ACTIVE ){
					
					has_been_active = true;
				}
			}
		}
		
		protected int
		getState()
		{
			return( state );
		}
		
		protected boolean
		hasBeenActive()
		{
			return( has_been_active );
		}
		
		protected void
		addRequest(
			ProxyLocalRequest			request,
			ActiveRequestListener	listener )
		{
			ActiveRequest	ar;
			
			synchronized( this ){
				
				if ( state == STATE_ACTIVE ){
					
					ar = new ActiveRequest( request, listener );
					
					active_requests.put( request.getSequence(), ar );
					
				}else{
					
					ar = null;
				}
			}
			
			if ( ar == null ){
				
				listener.failed( this, request );
				
			}else{
										
				try{
					byte[]	key		= request.getKey();

					MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
					
					sha256.update( "TorProxyDHT::key".getBytes( Constants.UTF_8 ));
					
					sha256.update( key );
					
					byte[] derived_key = sha256.digest();
					
					Map proxy_request = new HashMap<>();

					proxy_request.put( "op_key", derived_key );
					proxy_request.put( "op_seq", request.getSequence());
					
					Map options = request.getOptions();
					
					if ( options != null && !options.isEmpty()){
						
						proxy_request.put( "op_options", options);
					}

					if ( request.getType() == ProxyLocalRequest.RT_PUT ){
						
						Map value = ((ProxyLocalRequestPut)request).getValue();
						
						value = new HashMap<>( value );	// copy as we add to it
						
						value.put( "h", tep_pure_host_bytes );
						value.put( "p", tep_pure_port );

						byte[] bytes = BEncoder.encode(value);
						
						byte[] sig = tep_pure.sign( bytes );
												
						value.put( "z", sig );
				
						byte[] masked_value = BEncoder.encode( value );
						
						maskValue( key, masked_value );
												
						proxy_request.put( "op_type", PROXY_OP_PUT );
						proxy_request.put( "op_value", masked_value );
											
					}else if ( request.getType() == ProxyLocalRequest.RT_GET ){
						
						proxy_request.put( "op_type", PROXY_OP_GET );
						
					}else if ( request.getType() == ProxyLocalRequest.RT_REMOVE ){
						
						proxy_request.put( "op_type", PROXY_OP_REMOVE );
						
					}else{
						
						throw( new Exception( "eh?" ));
					}
					
					send( MT_PROXY_OP_REQUEST, proxy_request );

				}catch( Throwable e ){
					
					Debug.out( e );
					
					synchronized( this ){
						
						active_requests.remove( request.getSequence());
					}
					
					ar.setFailed();
				}
			}
		}
		
		protected void
		checkRequestTimeouts()
		{
			long now = SystemTime.getMonotonousTime();
			
			List<ActiveRequest>	failed = new ArrayList<>();
			
			synchronized( this ){
			
				Iterator<ActiveRequest>	it = active_requests.values().iterator();
				
				while( it.hasNext()){
					
					ActiveRequest ar = it.next();
					
					ProxyLocalRequest request = ar.getProxyRequest();
					
					if ( request.getType() == ProxyLocalRequest.RT_GET ){
						
						ProxyLocalRequestGet get = (ProxyLocalRequestGet)request;
						
						if ( now - get.getStartTime() > get.getTimeout()){
														
							failed.add( ar );
							
							it.remove();
						}						
						
					}
				}
			}
			
			for ( ActiveRequest ar: failed ){
				
				ar.setFailed();
			}
		}
		
		@Override
		public void 
		connected(
			GenericMessageConnection connection )
		{
			setConnected();
			
			try{
				Map payload = new TreeMap<>();
				
				payload.put( "source_host", tep_pure_host );
				payload.put( "source_port", tep_pure_port );
				payload.put( "target", getHost());
				payload.put( "uid", local_uid );
				
				byte[] bytes = BEncoder.encode(payload);
				
				byte[] sig = tep_pure.sign( bytes );
				
				Map map = new HashMap<>();
					
				map.put( "payload", payload );
				map.put( "sig", sig );
				
				map.put( "min_ver", MIN_SERVER_VERSION );
				
				send( MT_PROXY_ALLOC_REQUEST, map );
				
			}catch( Throwable  e ){
				
				failed( e );
			}
		}
				
		@Override
		public void 
		receive(
			int		type,
			Map		map )
		
			throws Exception
		{
			if ( type == MT_PROXY_ALLOC_OK_REPLY ){
				
				if ( getState() == STATE_INITIALISING ){
					
					map = BDecoder.decodeStrings( map );
					
					remote_iid = (String)map.get( "iid" );
					
					setState( STATE_ACTIVE );
					
					log( "Proxy client setup complete: iid=" + remote_iid );
					
					proxyClientSetupComplete( this, remote_iid );
				}
			}else if ( type == MT_PROXY_ALLOC_FAIL_REPLY ){
				
				map = BDecoder.decodeStrings( map );
				
				List<Map> contacts = (List<Map>)map.get( "contacts" );
				
				List<InetSocketAddress> isas = new ArrayList<>();
				
				for ( Map m: contacts ){
					
					String	host = (String)m.get( "host" );
					int		port = ((Number)m.get( "port" )).intValue();
					
					InetSocketAddress isa = InetSocketAddress.createUnresolved(host, port);
					
					isas.add( isa );
				}
				
				addBackupContacts( isas );
				
				log( "Proxy client setup failed: " + map );
				
				close();
				
			}else if ( type == MT_PROXY_OP_REPLY ){
				
				long seq = ((Number)map.get( "op_seq" )).longValue();
				
				ActiveRequest	ar;
				
				synchronized( this ){
					
					ar = active_requests.remove( seq );
				}
				
				if ( ar == null ){
					
					throw( new Exception( "Reply to unknown request received" ));
				}
				
				try{
					ProxyLocalRequest request = ar.getProxyRequest();
					
					int request_type = request.getType();
					
					if ( request_type == ProxyLocalRequest.RT_GET ){
						
						List<byte[]> l_values = (List<byte[]>)map.get( "op_values" );
						
						List<Map> values = new ArrayList<>();
						
						for ( byte[] masked_value: l_values ){
							
							maskValue( request.getKey(), masked_value );
							
							Map value = BDecoder.decode( masked_value );
							
							byte[]	sig = (byte[])value.remove( "z" );
							
							byte[]	host_bytes	= (byte[])value.get( "h" );
							
							PublicKey source_pk = I2PHelperPlugin.TorEndpoint.getPublicKey( host_bytes );
							
							byte[] value_bytes = BEncoder.encode( value );
	
							if ( I2PHelperPlugin.TorEndpoint.verify( source_pk, value_bytes, sig )){
								
									// don't remove "h" as some uses rely on it, replace with
									// actual host
								
								value.put( "h", I2PHelperPlugin.TorEndpoint.bytesToOnion( host_bytes ));
								
								values.add( value );
								
							}else{
								
								log( "value verification failed, ignoring" );
							}
						}
						
						((ProxyLocalRequestGet)request).setValues( values );
					}
					
					ar.setComplete();
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}else{
				
				throw( new Exception( "Unknown message type: " + type ));
			}
		}
		
		@Override
		protected void 
		setClosed()
		{
			setState( STATE_FAILED );
			
			List<ActiveRequest>	failed_requests;
			
			synchronized( this ){
				
				failed_requests = new ArrayList<>( active_requests.values());
				
				active_requests.clear();
			}
			
			for ( ActiveRequest ar: failed_requests ){
				
				try{
					ar.setFailed();
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
		}
		
		@Override
		protected String
		getString()
		{
			return( super.getString() + "; state=" + getState());
		}
		
		private class
		ActiveRequest
		{
			final ProxyLocalRequest			request;
			final ActiveRequestListener	listener;
			
			private AtomicBoolean done = new AtomicBoolean();
			
			ActiveRequest(
				ProxyLocalRequest			_request,
				ActiveRequestListener	_listener )
			{
				request 	= _request;
				listener	= _listener;
			}
			
			protected ProxyLocalRequest
			getProxyRequest()
			{
				return( request );
			}
			
			protected void
			setFailed()
			{
				if ( !done.compareAndSet( false, true )){
					
					return;
				}
				
				
				listener.failed( OutboundConnectionProxy.this, request );
			}
			
			protected void
			setComplete()
			{
				if ( !done.compareAndSet( false, true )){
					
					return;
				}
				
				listener.complete( OutboundConnectionProxy.this, request );
			}
		}
	}
	
	private class
	OutboundConnectionProxyProbe
		extends OutboundConnection
	{
		final InboundConnectionProxy	for_connection;
		final String					uid;
		
		protected volatile boolean	success;
		
		private
		OutboundConnectionProxyProbe(
			InboundConnectionProxy	_for_connection,
			InetSocketAddress		target,
			String					_uid )
		
			throws Exception
		{
			super( target );
			
			for_connection	= _for_connection;
			uid				= _uid;
			
			setDisconnectAfterSeconds( 60 );
		}
		
		@Override
		protected String
		getName()
		{
			return( "Probe out" );
		}
		
		@Override
		public void 
		connected(
			GenericMessageConnection connection )
		{
			setConnected();
			
			try{
					// we use the probe to at least show that the originator is a real service
				
				Map map = new HashMap<>();
				
				map.put( "uid", uid );
				map.put( "source_host", tep_mix.getHost());
				
				send( MT_PROXY_PROBE_REQUEST, map );
				
			}catch( Throwable  e ){
				
				failed( e );
			}
		}
				
		@Override
		public void 
		receive(
			int		type,
			Map		map )
		{
			if ( type == MT_PROXY_PROBE_REPLY ){
				
				success = true;
				
				for_connection.setProbeReplyReceived();
			}
				
			close();
		}
		
		@Override
		protected void 
		setClosed()
		{
			if ( !success ){
				
				for_connection.setProbeFailed();
			}
		}
	}
	
	private abstract class
	InboundConnection
		extends Connection
	{
		private
		InboundConnection(
			GenericMessageConnection		gmc )
		{
			setConnection( gmc );
			
			setConnected();
		}
				
		@Override
		public void 
		connected(
			GenericMessageConnection connection )
		{
			// nothing here
		}
	}
	
	public interface
	ProxyRemoteRequestListener
	{
		public void
		complete(
			List<byte[]>	values );
	}

	private abstract class
	ProxyRemoteRequest
	{
		public static final int RT_PUT			= 1;
		public static final int RT_GET			= 2;
		public static final int RT_REMOVE		= 3;

		private final long							create_time = SystemTime.getMonotonousTime();
		
		private final InboundConnectionProxy		proxy;
		private final int							type;
		private final byte[]						key;
		private final ProxyRemoteRequestListener	listener;
		
		private AtomicBoolean	done = new AtomicBoolean();
		
		protected
		ProxyRemoteRequest(
			InboundConnectionProxy		_proxy,
			int							_type,
			byte[]						_key,
			ProxyRemoteRequestListener	_listener )
		{
			proxy		= _proxy;
			type		= _type;
			key			= _key;
			listener	= _listener;
		}
		
		protected InboundConnectionProxy
		getProxy()
		{
			return( proxy );
		}
		
		protected int
		getType()
		{
			return( type );
		}
		
		protected long
		getCreateTime()
		{
			return( create_time );
		}
		
		protected byte[]
		getKey()
		{
			return( key );
		}
		
		protected abstract void
		execute();
		
		protected void
		requestComplete()
		{
			requestComplete( null );
		}
			
		protected void
		requestComplete(
			List<byte[]> 	values )
		{
			if ( !done.compareAndSet( false, true )){
				
				return;
			}

			try{
				listener.complete( values );
				
			}catch( Throwable e ){
				
				Debug.out( e );
			}
			
			proxy.remoteRequestComplete( this );
		}
		
		protected void
		requestFailed()
		{
			if ( !done.compareAndSet( false, true )){
				
				return;
			}

			try{
				listener.complete( null );
				
			}catch( Throwable e ){
				
				Debug.out( e );
			}
			
			proxy.remoteRequestComplete( this );
		}
	}
	
	private class
	ProxyRemoteRequestPut
		extends ProxyRemoteRequest
	{
		private final byte[]		value;
		private final Map			options;
		
		protected
		ProxyRemoteRequestPut(
			InboundConnectionProxy		proxy,
			byte[]						key,
			byte[]						_value,
			Map							_options,
			ProxyRemoteRequestListener	listener )
		{
			super( proxy, RT_PUT, key, listener );
			
			value	= _value;
			options	= _options;
		}
		
		protected void
		execute()
		{
			try{
				DHTPluginInterface dht = ddb.getDHTPlugin();
								
				byte	flags = DHTPluginInterface.FLAG_SINGLE_VALUE | DHTPluginInterface.FLAG_ANON;
				
				if ( options != null ){
					
					Number f = (Number)options.get( "f" );
					
					if ( f != null ){
						
						byte opt_f = f.byteValue();
						
						opt_f &= ( DHTPluginInterface.FLAG_SEEDING | DHTPluginInterface.FLAG_DOWNLOADING );
						
						flags |= opt_f;
					}
				}
				
				byte[] original_key = getKey();
								
				MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
				
				sha256.update( "TorProxyDHT::remote_mask".getBytes( Constants.UTF_8 ));
				
				sha256.update( original_key );
				
				byte[] masked_key = sha256.digest();

				getProxy().addKeyState( original_key );

				dht.put( 
					masked_key, 
					"TPD write: " + ByteFormatter.encodeString(masked_key), 
					value, 
					flags, 
					new DHTPluginOperationAdapter()
					{
						@Override
						public void 
						complete(
							byte[]		masked_key, 
							boolean		timeout_occurred )
						{
							requestComplete();
						}
					});
			}catch( Throwable  e ){
				
				Debug.out(e);
				
				requestFailed();
			}
		}
	}
	
	private class
	ProxyRemoteRequestGet
		extends ProxyRemoteRequest
	{
		private final Map			options;
		
		private long	timeout		= 2*60*1000;

		protected
		ProxyRemoteRequestGet(
			InboundConnectionProxy		proxy,
			byte[]						key,
			Map							_options,
			ProxyRemoteRequestListener	listener )
		{
			super( proxy, RT_GET, key, listener );
			
			options	= _options;
			
			if ( options != null ){
				
				Number t = (Number)options.get( "t" );
				
				if ( t != null ){
					
					long opt_timeout = t.intValue();
					
					timeout = Math.min( timeout, opt_timeout );
				}
			}
		}
		
		protected long
		getTimeout()
		{
			return( timeout );
		}
		
		protected void
		execute()
		{
			try{
				long queued = SystemTime.getMonotonousTime() - getCreateTime();
				
				timeout -= queued;
				
				if ( timeout < 1000 ){
					
					requestFailed();
					
				}else{
					
					DHTPluginInterface dht = ddb.getDHTPlugin();
					
					byte	flags		= 0;
					int		num_want	= 32;
					
					if ( options != null ){
						
						Number f = (Number)options.get( "f" );
						
						if ( f != null ){
							
							byte opt_f = f.byteValue();
							
							opt_f &= ( DHTPluginInterface.FLAG_SEEDING | DHTPluginInterface.FLAG_DOWNLOADING );
							
							flags |= opt_f;
						}
						
						Number n = (Number)options.get( "n" );
						
						if ( n != null ){
							
							int opt_num_want = n.intValue();
							
							if ( opt_num_want < 128 ){
								
								num_want = opt_num_want;
							}
						}
					}
						
					byte[] original_key = getKey();
					
					MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
					
					sha256.update( "TorProxyDHT::remote_mask".getBytes( Constants.UTF_8 ));
					
					sha256.update( original_key );
					
					byte[] masked_key = sha256.digest();

					dht.get(
						masked_key, 
						"TPD read: " + ByteFormatter.encodeString(masked_key), 
						flags,
						num_want,
						timeout,
						false, false,
						new DHTPluginOperationAdapter()
						{
							ByteArrayHashMap<String>	result = new ByteArrayHashMap<>();
							
							@Override
							public void 
							valueRead(
								DHTPluginContact	originator, 
								DHTPluginValue		value )
							{
								synchronized( result ){
								
									result.put( value.getValue(), "");
								}
							}
							
							@Override
							public void 
							complete(
								byte[]		masked_key, 
								boolean		timeout_occurred )
							{
								requestComplete( new ArrayList<byte[]>( result.keys()));
							}
						});
				}
			}catch( Throwable  e ){
				
				Debug.out(e);
				
				requestFailed();
			}
		}
	}
	
	private class
	ProxyRemoteRequestRemove
		extends ProxyRemoteRequest
	{
		protected
		ProxyRemoteRequestRemove(
			InboundConnectionProxy		proxy,
			byte[]						key,
			ProxyRemoteRequestListener	listener )
		{
			super( proxy, RT_REMOVE, key, listener );
		}
		
		protected void
		execute()
		{
			try{
				DHTPluginInterface dht = ddb.getDHTPlugin();
				
				byte[] original_key = getKey();
				
				MessageDigest sha256 = MessageDigest.getInstance( "SHA-256" );
				
				sha256.update( "TorProxyDHT::remote_mask".getBytes( Constants.UTF_8 ));
				
				sha256.update( original_key );
				
				byte[] masked_key = sha256.digest();
				
				dht.remove( 
					masked_key, 
					"TPD remove: " + ByteFormatter.encodeString(masked_key), 
					new DHTPluginOperationAdapter()
					{
						@Override
						public void 
						complete(
							byte[]		masked_key, 
							boolean		timeout_occurred )
						{
							requestComplete();
							
							getProxy().removeKeyState( original_key );
						}
					});
				
			}catch( Throwable  e ){
				
				Debug.out(e);
				
				requestFailed();
			}
		}
	}
	
	private class
	InboundConnectionProxy
		extends InboundConnection
	{
		public static final int STATE_INITIALISING	= 0;
		public static final int STATE_PROBE_SENT	= 1;
		public static final int STATE_PROBE_FAILED	= 2;
		public static final int STATE_ACTIVE		= 3;
		public static final int STATE_FAILED		= 4;
				
		private volatile int state	= STATE_INITIALISING;

		private String		source_host;
		private PublicKey 	source_pk;
		
		private
		InboundConnectionProxy(
			GenericMessageConnection		gmc )
		{
			super( gmc );
		}
			
		@Override
		protected String
		getName()
		{
			return( "Proxy in" );
		}
		
		protected void
		setState(
			int		_state )
		{
			synchronized( this ){
				
				if ( state == STATE_FAILED ){
					
					return;
				}
				
				state = _state;
			}
		}
		
		protected int
		getState()
		{
			return( state );
		}
		
		@Override
		protected void 
		receive(
			int 	type,
			Map 	request ) 
			
			throws Exception
		{
			if ( type == MT_PROXY_ALLOC_REQUEST ){
				
				if ( !ENABLE_PROXY_SERVER ){
					
					throw( new Exception( "Proxy server disabled" ));
				}
				
				Number i_ver = (Number)request.get( "ver" );
				
				int ver = i_ver==null?1:i_ver.intValue();
				
				Number i_min_ver = (Number)request.get( "min_ver" );

				int min_ver = i_min_ver==null?1:i_min_ver.intValue();
				
				Map payload_raw = (Map)request.get( "payload" );
												
				Map payload = BDecoder.decodeStrings( payload_raw );
				
				String target	= (String)payload.get( "target" );
				
				if ( target != null ){
					
					if ( !target.equals( tep_mix.getHost())){
					
						throw( new Exception( "target host mismatch" ));
					}
				}
				
				source_host	= (String)payload.get( "source_host" );
				
				boolean denied = false;
				
				if ( min_ver > I2PHelperAltNetHandlerTor.LOCAL_VERSION ){
					
					denied = true;
					
				}else{
					
					synchronized( connections ){
						
						if ( server_proxies.size() >= MAX_SERVER_PROXIES ){
		
							denied = true;
							
						}else{
						
							for ( InboundConnectionProxy sp: server_proxies ){
								
								if ( source_host.equals( sp.source_host )){
									
									throw( new Exception( "Duplicate proxy" ));
								}
							}
							
							server_proxies.add( this );
						}
					}
				}
				
				if ( denied ){
					
					Map reply = new HashMap<>();
										
					List<Map> l_contacts = new ArrayList<>();
					
					reply.put( "contacts", l_contacts );

					DHTTransportAlternativeNetwork net = DHTUDPUtils.getAlternativeNetwork( DHTTransportAlternativeNetwork.AT_TOR );
					
					if ( net != null ){
					
						List<DHTTransportAlternativeContact> contacts = DHTUDPUtils.getAlternativeContacts( DHTTransportAlternativeNetwork.AT_TOR, 16 );

						Collections.shuffle( contacts );
							
						for ( DHTTransportAlternativeContact contact: contacts ){
														
							int contact_version = contact.getVersion();
							
							if ( contact_version == 0 || contact_version >= min_ver ){
								
								Map m = new HashMap<>();
								
								InetSocketAddress isa = net.getNotionalAddress( contact );
								
								m.put( "host", AddressUtils.getHostAddress( isa ));
								m.put( "port", isa.getPort());
								
								l_contacts.add( m );								
								
								if ( l_contacts.size() == 5 ){
									
									break;
								}
							}
						}
					}
					
					send( MT_PROXY_ALLOC_FAIL_REPLY, reply );

						// if we fail immediately the reply doesn't get sent...
					
					setDisconnectAfterSeconds( 10 );
					
					return;
				}
				
				int		source_port	= ((Number)payload.get( "source_port" )).intValue();
				String	uid			= (String)payload.get( "uid" );
				
				source_pk = I2PHelperPlugin.TorEndpoint.getPublicKey( source_host );
				
				byte[] sig = (byte[])request.get( "sig" );

				byte[] payload_bytes = BEncoder.encode( payload_raw );

				if ( !I2PHelperPlugin.TorEndpoint.verify( source_pk, payload_bytes, sig )){
					
					throw( new Exception( "Signature verification failed" ));
				}
				
				InetSocketAddress source_isa = InetSocketAddress.createUnresolved( source_host, source_port);
				
				new OutboundConnectionProxyProbe( this, source_isa, uid );
				
				setState( STATE_PROBE_SENT );
				
			}else if ( type == MT_PROXY_OP_REQUEST ){
				
				int		op_type = ((Number)request.get( "op_type" )).intValue();
				long	seq		= ((Number)request.get( "op_seq" )).longValue();
				
				byte[] 	key		= (byte[])request.get( "op_key" );
				Map		options	= (Map)request.get( "op_options" );
				
				Map reply = new HashMap<>();

				reply.put( "op_seq", seq );

				switch( op_type ){
				
					case PROXY_OP_PUT:{
	
						byte[] value = (byte[])request.get( "op_value" );
						
						proxyRemotePut(
							key, value, options, (v)->{
								
								send( MT_PROXY_OP_REPLY, reply );
							});

																								
						break;
					}
					case PROXY_OP_GET:{
						
						proxyRemoteGet( key, options, (v)->{
								
							reply.put( "op_values", v );

							send( MT_PROXY_OP_REPLY, reply );
						});
																		
						break;
					}
					case PROXY_OP_REMOVE:{
						
						proxyRemoteRemove( key, (v)->{
								
							send( MT_PROXY_OP_REPLY, reply );
						});
																		
						break;
					}
					default:{
						
						throw( new Exception( "Invalid op type: " + op_type ));
					}
				}
						
				

			}else{
					
				throw( new Exception( "Invalid message type" ));
			}
		}
		
		protected void
		setProbeReplyReceived()
		{
			log( "Proxy server setup complete: iid=" + instance_id );
		
			setState( STATE_ACTIVE );

			Map reply = new HashMap<>();
				
			reply.put( "iid", instance_id );
			
			send( MT_PROXY_ALLOC_OK_REPLY, reply );
			
			proxyServerSetupComplete( this,instance_id );
		}
		
		protected void
		setProbeFailed()
		{
			log( "Probe failed" );
			
			setState( STATE_PROBE_FAILED );
			
			Map reply = new HashMap<>();
						
			List<Map> l_contacts = new ArrayList<>();
			
			reply.put( "contacts", l_contacts );
			
			send( MT_PROXY_ALLOC_FAIL_REPLY, reply );

				// if we fail immediately the reply doesn't get sent...
			
			setDisconnectAfterSeconds( 10 );
		}
				
		private void
		proxyRemotePut(
			byte[]				key,
			byte[]				value,
			Map					options,
			ProxyRemoteRequestListener	listener )
		{
			ProxyRemoteRequestPut	request = new ProxyRemoteRequestPut( this, key, value, options, listener );
			
			addRemoteRequest( request );
		}
		
		private void
		proxyRemoteGet(
			byte[]					key,
			Map						options,
			ProxyRemoteRequestListener		listener )
		{
			ProxyRemoteRequestGet	request = new ProxyRemoteRequestGet( this, key, options, listener );
			
			addRemoteRequest( request );
		}
		
		private void
		proxyRemoteRemove(
			byte[]				key,
			ProxyRemoteRequestListener	listener )
		
			throws Exception
		{
			ProxyRemoteRequestRemove request = new ProxyRemoteRequestRemove( this, key, listener );
			
			addRemoteRequest( request );
		}
		
		private ByteArrayHashMap<String>	remote_key_state = new ByteArrayHashMap<>();

			// get requests at index 0, others index 1
						
		private LinkedList<ProxyRemoteRequest>[]	queued_requests = new LinkedList[2];
		
		{
			queued_requests[0] = new LinkedList<>();
			queued_requests[1] = new LinkedList<>();
		}
		
		private int[]	active_request_count	= { 0, 0 };

		private final int[] MAX_ACTIVE_REQUESTS = { 16, 16 };
		
		private final int MAX_QUEUED_REQUESTS	= 128;
		
		private void
		addRemoteRequest(
			ProxyRemoteRequest		request )
		{
			if ( getState() != STATE_ACTIVE ){
				
				return;
			}
			
			ProxyRemoteRequest	to_exec = null;
			
			synchronized( remote_key_state ){
								
				int type = request.getType() == ProxyRemoteRequest.RT_GET?0:1;
								
				if ( 	queued_requests[0].size() + queued_requests[1].size() > MAX_QUEUED_REQUESTS || 
						remote_key_state.size() > MAX_KEY_STATE ){
					
						// silently ignore
					
					return;
				}
				
				LinkedList<ProxyRemoteRequest>	requests 	= queued_requests[type];

				requests.addFirst( request );
				
				if ( active_request_count[type] < MAX_ACTIVE_REQUESTS[type] ){
					
					active_request_count[type]++;
					
					to_exec = requests.removeLast();
				}
			}
			
			if ( to_exec != null ){
				
				to_exec.execute();
			}
		}
		
		private void
		remoteRequestComplete(
			ProxyRemoteRequest		request )
		{
			if ( getState() != STATE_ACTIVE ){
				
				return;
			}

			int type = request.getType() == ProxyRemoteRequest.RT_GET?0:1;

			ProxyRemoteRequest	to_exec = null;
			
			synchronized( remote_key_state ){
				
				LinkedList<ProxyRemoteRequest>	requests 	= queued_requests[type];

				active_request_count[type]--;
				
				if ( active_request_count[type] < MAX_ACTIVE_REQUESTS[type] && !requests.isEmpty()){
					
					active_request_count[type]++;
					
					to_exec = requests.removeLast();
				}
			}
			
			if ( to_exec != null ){
				
				to_exec.execute();
			}
		}
		
		protected void
		addKeyState(
			byte[]		key )
		{
			synchronized( remote_key_state ){
				
				remote_key_state.put( key, "" );
			}
		}
		
		protected void
		removeKeyState(
			byte[]		key )
		{
			synchronized( remote_key_state ){
			
				remote_key_state.remove( key );
			}
		}
		
		protected void
		checkRequests()
		{
			List<ProxyRemoteRequestGet>	failed = new ArrayList<>();
			
			synchronized( remote_key_state ){
				
				log( "RKS " + source_host + " size=" + remote_key_state.size() + 
						", active=" + active_request_count[0] + "/" +active_request_count[1] + 
						", queued=" + queued_requests[0].size() + "/" + queued_requests[1].size());
				
				LinkedList<ProxyRemoteRequest>	requests = queued_requests[0];	// get queue
				
				if ( requests.isEmpty()){
					
					return;
				}
				
				long now = SystemTime.getMonotonousTime();
				
				Iterator<ProxyRemoteRequest> it = requests.iterator();
				
				while( it.hasNext()){
					
					ProxyRemoteRequestGet request = (ProxyRemoteRequestGet)it.next();
									
					long elapsed = now - request.getCreateTime();
										
					if ( elapsed > request.getTimeout()){
						
						it.remove();
						
						failed.add( request );
					}	
				}
			}
			
			for ( ProxyRemoteRequestGet request: failed ){
				
				request.requestFailed();
			}
		}
		
		@Override
		protected void 
		setClosed()
		{
			setState( STATE_FAILED );
			
			synchronized( remote_key_state ){
				
				queued_requests[0].clear();
				queued_requests[1].clear();
			}
		}
		
		protected String
		getString()
		{
			return( super.getString() + ": " + source_host + "; state=" + getState());
		}
	}
	
	private class
	InboundConnectionProbe
		extends InboundConnection
	{
		private String		source_host;
		
		private
		InboundConnectionProbe(
			GenericMessageConnection		gmc )
		{
			super( gmc );
		}
			
		@Override
		protected String
		getName()
		{
			return( "Probe in" );
		}
		
		@Override
		protected void 
		receive(
			int 	type,
			Map 	request ) 
			
			throws Exception
		{
			if ( type == MT_PROXY_PROBE_REQUEST ){
								
				setDisconnectAfterSeconds( 60 );

				request = BDecoder.decodeStrings( request );
				
				String uid = (String)request.get( "uid" );
				
				source_host = (String)request.get( "source_host" );
				
				OutboundConnectionProxy cp = current_client_proxy;
				
				if ( cp != null && cp.getLocalUID().equals( uid )){
				
					Map reply = new HashMap<>();
				
					send( MT_PROXY_PROBE_REPLY, reply );
					
				}else{
					
					throw( new Exception( "Probe request UID mismatch" ));
				}
			}else{
				
				throw( new Exception( "Invalid message type" ));
			}
		}
	}
	
	private interface
	ActiveRequestListener
	{
		public void
		complete(
			OutboundConnectionProxy		proxy,
			ProxyLocalRequest				request );
		
		public void
		failed(
			OutboundConnectionProxy		proxy,
			ProxyLocalRequest				request );
	}
	
	private abstract class
	ProxyLocalRequest
	{
		public static final int RT_PUT			= 1;
		public static final int RT_GET			= 2;
		public static final int RT_REMOVE		= 3;
	
		private final long			start = SystemTime.getMonotonousTime();
		
		private final long			seq	= proxy_request_seq.incrementAndGet();
		private final byte[]		key;
		
		private Map	options;
		
		protected
		ProxyLocalRequest(
			byte[]		_key )
		{
			key		= _key;
		}
		
		protected long
		getSequence()
		{
			return( seq );
		}
		
		protected long
		getStartTime()
		{
			return( start );
		}
		
		protected abstract int
		getType();
		
		protected byte[]
		getKey()
		{
			return( key );
		}
		
		protected Map
		getOptions()
		{
			return( options );
		}
		
		protected void
		setOptions(
			Map		_options )
		{
			options	= _options;
		}
	}
	
	private class
	ProxyLocalRequestPut
		extends ProxyLocalRequest
	{
		private final Map	value;
		
		protected
		ProxyLocalRequestPut(
			byte[]		_key,
			Map			_value )
		{
			super( _key );
			
			value = _value;
		}
		
		protected int
		getType()
		{
			return( RT_PUT );
		}
		
		protected Map
		getValue()
		{
			return( value );
		}
	}
	
	private class
	ProxyLocalRequestGet
		extends ProxyLocalRequest
	{
		private long				timeout = 2*60*1000;
		private	ProxyGetListener	listener;
		
		private AtomicBoolean done = new AtomicBoolean();
		
		protected
		ProxyLocalRequestGet(
			byte[]		_key )
		{
			super( _key );
		}
		
		protected int
		getType()
		{
			return( RT_GET );
		}
		
		protected void
		setTimeout(
			long	t )
		{
			timeout = t;
		}
		
		protected long
		getTimeout()
		{
			return( timeout );
		}
		
		protected void
		setListener(
			ProxyGetListener		_listener )
		{
			listener	= _listener;
		}
		
		protected void
		setValues(
			List<Map> values )
		{			
			if ( listener != null ){
				
				try{
					listener.valuesRead( getKey(), values );
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
		}
		
		protected void
		setFailed()
		{
			if ( !done.compareAndSet( false, true )){
				
				return;
			}
			
			if ( listener != null ){
				
				try{
					listener.complete( getKey(), true );
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
		}
		
		protected void
		setComplete()
		{
			if ( !done.compareAndSet( false, true )){
				
				return;
			}
			
			if ( listener != null ){
			
				try{
					listener.complete( getKey(), false );
					
				}catch( Throwable e ){
					
					Debug.out( e );
				}
			}
		}
	}
	
	private class
	ProxyLocalRequestRemove
		extends ProxyLocalRequest
	{
		private final List<Map>		values = new ArrayList<>();
		
		protected
		ProxyLocalRequestRemove(
			byte[]		_key )
		{
			super( _key );
		}
		
		protected int
		getType()
		{
			return( RT_REMOVE );
		}
	}
	
	public interface
	ProxyGetListener
	{
		public void
		valuesRead(
			byte[]				key,
			List<Map>			value );
		
		public void
		complete(
			byte[]				key,
			boolean				timeout );
	}
	
	public interface
	TorProxyDHTListener
	{
		public void
		proxyValueRead(
			InetSocketAddress	originator,
			boolean				is_seed,
			boolean				crypto_required );
		
		public void
		proxyComplete(
			byte[]		key,
			boolean		timeout );
	}
}