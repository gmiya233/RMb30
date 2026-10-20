package cn.gmiya.malodylink;

import android.content.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Shared sync gate for the foreground service and first-pairing helper. */
public final class LocalScoreSync {
    public static final String UPDATED="cn.gmiya.malodylink.LOCAL_SYNC_UPDATED";
    private static final AtomicBoolean busy=new AtomicBoolean();
    public static volatile String currentProgress="";
    public static boolean isBusy(){return busy.get();}
    private static void connect(Context c,InternalAdb adb,int manualPort)throws Exception {
        if(adb.isConnected())return;
        int port=manualPort;
        if(port==0)try{port=LocalAdbDiscovery.find(c,LocalAdbDiscovery.CONNECT,5000);}
        catch(java.io.IOException discoveryFailed){port=c.getSharedPreferences("reader",0).getInt("connect_port",0);if(port==0)throw discoveryFailed;}
        try{adb.connectLocal(port);}
        catch(Exception first){
            if(manualPort!=0||authentication(first))throw first;
            adb.disconnect();port=LocalAdbDiscovery.find(c,LocalAdbDiscovery.CONNECT,6000);adb.connectLocal(port);
        }
        c.getSharedPreferences("reader",0).edit().putInt("connect_port",port).apply();
    }
    static boolean authentication(Throwable error){
        for(Throwable e=error;e!=null;e=e.getCause()){
            String kind=e.getClass().getSimpleName();
            if(kind.contains("PairingRequired")||kind.contains("AuthenticationFailed"))return true;
        }return false;
    }
    private interface Operation<T>{T run()throws Exception;}
    private static <T>T recover(Context c,InternalAdb adb,Engine.Progress progress,Operation<T> action)throws Exception {
        for(int attempt=0;;attempt++)try{return action.run();}
        catch(InternalAdb.TransportException disconnected){
            if(attempt>=2)throw disconnected;
            progress.update("连接暂时中断，正在恢复（"+(attempt+1)+"/2）；已收录成绩保留…");
            adb.disconnect();connect(c,adb,0);
        }
    }
    static Engine.Reader recovering(Context c,InternalAdb adb,Engine.Progress progress){
        Engine.Reader source=adb.reader();
        return new Engine.Reader(){
            public List<String> list(String type)throws Exception{return recover(c,adb,progress,()->source.list(type));}
            public byte[] file(String path)throws Exception{return recover(c,adb,progress,()->source.file(path));}
            public String fingerprint(String path)throws Exception{return source.fingerprint(path);}
        };
    }
    public static String run(Context context,int manualPort,Engine.Progress callback)throws Exception {
        if(!busy.compareAndSet(false,true))throw new IllegalStateException("正在同步成绩，请稍候");
        Engine.Progress progress=text->{currentProgress=text;callback.update(text);};
        try{
            InternalAdb adb=InternalAdb.get(context);progress.update("正在连接本机读取…");connect(context,adb,manualPort);
            // A successful authenticated connection is stronger evidence than a stale local pairing flag.
            context.getSharedPreferences("reader",0).edit().putBoolean("paired",true).apply();
            Engine engine=new Engine(context);String warning="";
            try{DefaultCatalog.refresh(context,engine,false,progress);}catch(Exception updateFailed){warning="谱库更新未完成，暂按缓存目录筛选。";}
            progress.update("正在检查本机谱面…");String result=warning+engine.scan(recovering(context,adb,progress),progress);
            context.getSharedPreferences("reader",0).edit().putLong("last_sync",System.currentTimeMillis()).putString("last_result",result).putBoolean("last_ok",true).apply();
            return result;
        }catch(Exception e){
            SharedPreferences.Editor edit=context.getSharedPreferences("reader",0).edit().putBoolean("last_ok",false).putString("last_result",friendly(e));
            if(authentication(e))edit.putBoolean("paired",false);edit.apply();throw e;
        }finally{currentProgress="";busy.set(false);context.sendBroadcast(new Intent(UPDATED).setPackage(context.getPackageName()));}
    }
    public static String friendly(Throwable error){
        if(authentication(error))return "系统配对授权已失效，请重新配对；已收录成绩保留。";
        if(error instanceof InternalAdb.TransportException)return "连接恢复后仍未读完，请点「重试同步」；已收录成绩保留，无需先重新配对。";
        String detail=error.getMessage();
        if(error instanceof java.net.ConnectException||error instanceof java.net.SocketException)return "本机连接不可用，请确认无线调试开启后重试；通常无需重新配对。";
        return detail==null?"同步未完成，请重试；已收录成绩保留。":"同步未完成："+detail;
    }
}
