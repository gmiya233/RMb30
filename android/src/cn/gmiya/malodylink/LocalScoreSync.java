package cn.gmiya.malodylink;

import android.content.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One synchronization at a time, shared by the page and first-pairing helper. */
public final class LocalScoreSync {
    public static final String UPDATED="cn.gmiya.malodylink.LOCAL_SYNC_UPDATED";
    private static final AtomicBoolean busy=new AtomicBoolean();
    public static boolean isBusy(){return busy.get();}
    public static String run(Context context,int manualPort,Engine.Progress progress)throws Exception {
        if(!busy.compareAndSet(false,true))throw new IllegalStateException("正在同步成绩，请稍候");
        try{
            InternalAdb adb=InternalAdb.get(context);
            if(!adb.isConnected()){
                progress.update("正在连接本机读取…");
                int port=manualPort;
                if(port==0)try{port=LocalAdbDiscovery.find(context,LocalAdbDiscovery.CONNECT,5000);}catch(Exception discoveryFailed){port=context.getSharedPreferences("reader",0).getInt("connect_port",0);if(port==0)throw discoveryFailed;}
                adb.connectLocal(port);context.getSharedPreferences("reader",0).edit().putInt("connect_port",port).apply();
            }
            progress.update("正在读取 Malody 成绩文件…");
            Engine engine=new Engine(context);String warning="";
            try{DefaultCatalog.refresh(context,engine,false,progress);}catch(Exception updateFailed){warning="谱库更新未完成，暂按缓存目录筛选。";}
            String result=warning+engine.scan(adb.reader(),progress);
            context.getSharedPreferences("reader",0).edit().putLong("last_sync",System.currentTimeMillis()).putString("last_result",result).putBoolean("last_ok",true).apply();
            return result;
        }catch(Exception e){context.getSharedPreferences("reader",0).edit().putBoolean("last_ok",false).putString("last_result",friendly(e)).apply();throw e;}
        finally{busy.set(false);context.sendBroadcast(new Intent(UPDATED).setPackage(context.getPackageName()));}
    }
    public static String friendly(Throwable error){
        String kind=error.getClass().getSimpleName(),detail=error.getMessage();
        if(kind.contains("Pairing")||kind.contains("Authentication"))return "配对授权已失效，请点第 3 步重新配对。";
        if(kind.contains("Connect")||kind.contains("Socket")||error instanceof java.net.ConnectException)return "未连接到本机。请打开无线调试，再返回重试；通常无需重新配对。";
        return detail==null?"同步未完成，请打开无线调试后重试；仍失败可使用连接选项。":"同步未完成："+detail;
    }
}
