package cn.gmiya.malodylink;

import android.app.*;
import android.content.*;
import android.os.IBinder;
import java.util.concurrent.*;

/** Keep a user-started import alive when the reader page is left. */
public final class LocalSyncService extends Service {
    private static final String CHANNEL="local_score_sync";
    private static final int ID=32;
    public static volatile boolean active;
    private boolean submitted;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    public static synchronized void request(Context c,int port){
        if(active||LocalScoreSync.isBusy())return;
        active=true;
        try{c.startForegroundService(new Intent(c,LocalSyncService.class).putExtra("port",port));}
        catch(RuntimeException e){active=false;throw e;}
    }
    private Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,32,new Intent(this,InternalReaderActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Malody Link · 同步成绩").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();
    }
    @Override public void onCreate(){super.onCreate();active=true;
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"本机成绩同步",NotificationManager.IMPORTANCE_LOW));}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        startForeground(ID,notification("正在连接本机…"));if(submitted)return START_NOT_STICKY;submitted=true;
        final int port=intent==null?0:intent.getIntExtra("port",0);
        worker.execute(()->{
            String result;final long[] notified={0};
            try{result=LocalScoreSync.run(this,port,text->{
                sendBroadcast(new Intent(LocalScoreSync.UPDATED).setPackage(getPackageName()));
                long now=android.os.SystemClock.elapsedRealtime();if(now-notified[0]>700){notified[0]=now;((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(ID,notification(text));}
            });}catch(Exception e){result=LocalScoreSync.friendly(e);}
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(ID,notification(result));
            active=false;sendBroadcast(new Intent(LocalScoreSync.UPDATED).setPackage(getPackageName()));
            stopForeground(STOP_FOREGROUND_DETACH);stopSelf();
        });return START_NOT_STICKY;
    }
    @Override public void onDestroy(){active=false;worker.shutdown();super.onDestroy();}
}
