package cn.gmiya.malodylink;

import android.content.Context;
import android.net.nsd.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Discover only this phone, and always connect through loopback. */
public final class LocalAdbDiscovery implements AutoCloseable {
    public static final String PAIR="_adb-tls-pairing._tcp.",CONNECT="_adb-tls-connect._tcp.";
    public interface Found {void port(int port);}
    private static final ExecutorService lifecycle=Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"local-discovery-lifecycle");t.setDaemon(true);return t;});
    private final NsdManager manager;private volatile boolean closed,started;private final java.util.concurrent.atomic.AtomicBoolean stopQueued=new java.util.concurrent.atomic.AtomicBoolean();
    private final NsdManager.DiscoveryListener listener;
    public LocalAdbDiscovery(Context c,String type,Found found){
        manager=(NsdManager)c.getSystemService(Context.NSD_SERVICE);
        listener=new NsdManager.DiscoveryListener(){
            public void onDiscoveryStarted(String t){}public void onDiscoveryStopped(String t){}
            public void onStartDiscoveryFailed(String t,int error){}public void onStopDiscoveryFailed(String t,int error){}
            public void onServiceLost(NsdServiceInfo info){}
            public void onServiceFound(NsdServiceInfo info){if(closed)return;manager.resolveService(info,new NsdManager.ResolveListener(){
                public void onResolveFailed(NsdServiceInfo s,int e){}
                public void onServiceResolved(NsdServiceInfo s){if(!closed&&local(s.getHost())&&s.getPort()>0)found.port(s.getPort());}
            });}
        };
        lifecycle.execute(()->{if(closed)return;try{manager.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener);started=true;if(closed)stopAsync();}catch(Exception ignored){}});
    }
    private static boolean local(InetAddress address){try{return address!=null&&(address.isLoopbackAddress()||NetworkInterface.getByInetAddress(address)!=null);}catch(Exception e){return false;}}
    private void stopAsync(){if(stopQueued.compareAndSet(false,true))lifecycle.execute(()->{try{manager.stopServiceDiscovery(listener);}catch(Exception ignored){}});}
    @Override public void close(){closed=true;if(started)stopAsync();}
    public static int find(Context c,String type,long timeout)throws Exception {
        CountDownLatch ready=new CountDownLatch(1);int[] port={0};
        try(LocalAdbDiscovery d=new LocalAdbDiscovery(c,type,p->{port[0]=p;ready.countDown();})){if(!ready.await(timeout,TimeUnit.MILLISECONDS))throw new java.io.IOException("未找到本机无线调试，请确认开关已开启，或手动输入连接端口");return port[0];}
    }
}
