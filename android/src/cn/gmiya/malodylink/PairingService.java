package cn.gmiya.malodylink;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;

/** Short-lived pairing helper: enter the system code from the notification without closing its dialog. */
public final class PairingService extends Service {
    static final String CODE="pairing_code",CHANNEL="local_reader_pairing";
    private static final int ID=30;
    public static volatile boolean active;public static volatile String phase="";
    private LocalAdbDiscovery discovery;private volatile int port;private volatile boolean pairing;
    private final Handler handler=new Handler(Looper.getMainLooper());private final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onCreate(){super.onCreate();active=true;((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"本机成绩读取配对",NotificationManager.IMPORTANCE_HIGH));}
    @Override public android.os.IBinder onBind(Intent i){return null;}
    private Notification notification(String text,boolean input){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,InternalReaderActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder n=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Malody Link · 本机读取配对").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(input).setVisibility(Notification.VISIBILITY_PRIVATE);
        if(input){Intent reply=new Intent(this,PairCodeReceiver.class);PendingIntent pending=PendingIntent.getBroadcast(this,1,reply,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0));
            RemoteInput remote=new RemoteInput.Builder(CODE).setLabel("输入系统显示的六位配对码").build();n.addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_send,"输入配对码",pending).addRemoteInput(remote).setAllowGeneratedReplies(false).build());}
        return n.build();
    }
    private void notifyText(String text,boolean input){phase=text;((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(ID,notification(text,input));sendBroadcast(new Intent(LocalScoreSync.UPDATED).setPackage(getPackageName()));}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(pairing)return START_NOT_STICKY;
        phase="请点「使用配对码配对设备」，再在通知中输入六位码";
        startForeground(ID,notification(phase,true));
        if(intent!=null&&"PAIR".equals(intent.getAction())){
            String code=intent.getStringExtra(CODE);intent.removeExtra(CODE);
            if(pairing)return START_NOT_STICKY;
            if(code==null||!code.trim().matches("[0-9]{6}")){notifyText("配对码应为六位数字，请重新输入",true);return START_NOT_STICKY;}
            pairing=true;notifyText("正在完成系统配对…",false);final String entered=code.trim();final int targetPort=port;
            worker.execute(()->{String message;boolean paired=false;try{
                if(!InternalAdb.get(this).pairLocal(targetPort==0?LocalAdbDiscovery.find(this,LocalAdbDiscovery.PAIR,6000):targetPort,entered))throw new Exception("配对失败");
                getSharedPreferences("reader",0).edit().putBoolean("paired",true).apply();paired=true;
                notifyText("配对成功，正在自动同步成绩…",false);
                message="首次连接完成。"+LocalScoreSync.run(this,0,p->notifyText(p,false));
            }catch(Exception e){message=paired?"配对已完成。"+LocalScoreSync.friendly(e):"配对未完成，请重新开始配对并使用新的六位码。";}
                String done=message;handler.post(()->{notifyText(done,false);stopForeground(STOP_FOREGROUND_DETACH);stopSelf();});});
            return START_NOT_STICKY;
        }
        if(discovery==null)try{discovery=new LocalAdbDiscovery(this,LocalAdbDiscovery.PAIR,p->{port=p;if(!pairing)notifyText("已找到本机配对窗口。点下方输入六位配对码。",true);});}catch(Exception e){notifyText("无法发现配对窗口，请使用App内的手动配对",false);stopForeground(STOP_FOREGROUND_DETACH);stopSelf();}
        handler.removeCallbacksAndMessages(null);handler.postDelayed(()->{if(!pairing){notifyText("配对等待已结束，可回App重新开始",false);stopForeground(STOP_FOREGROUND_DETACH);stopSelf();}},180000);
        return START_NOT_STICKY;
    }
    @Override public void onDestroy(){active=false;phase="";handler.removeCallbacksAndMessages(null);if(discovery!=null)discovery.close();worker.shutdown();sendBroadcast(new Intent(LocalScoreSync.UPDATED).setPackage(getPackageName()));super.onDestroy();}
}
