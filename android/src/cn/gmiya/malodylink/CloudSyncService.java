package cn.gmiya.malodylink;

import android.app.*;
import android.content.*;
import android.os.IBinder;
import java.util.concurrent.*;

/** User-started cloud sync continues if the player leaves the page. */
public final class CloudSyncService extends Service {
    public static volatile boolean active;private boolean submitted;
    private static final String CHANNEL="cloud_score_sync";private static final int ID=33;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    public static void request(Context c,boolean upload){request(c,upload,false);}
    public static synchronized void request(Context c,boolean upload,boolean titleOnly){if(active||ScoreSyncGate.busy()||LocalSyncService.active)throw new IllegalStateException("正在同步，请稍候再操作云存档");active=true;try{c.startForegroundService(new Intent(c,CloudSyncService.class).putExtra("upload",upload).putExtra("title_only",titleOnly));}catch(RuntimeException e){active=false;throw e;}}
    private Notification notification(String text){PendingIntent open=PendingIntent.getActivity(this,33,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Malody Link · QQ云存档").setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(open).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).build();}
    @Override public void onCreate(){super.onCreate();active=true;((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"QQ云存档同步",NotificationManager.IMPORTANCE_LOW));}
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int id){startForeground(ID,notification("正在连接云存档…"));if(submitted)return START_NOT_STICKY;submitted=true;boolean upload=intent!=null&&intent.getBooleanExtra("upload",true);boolean titleOnly=intent!=null&&intent.getBooleanExtra("title_only",false);
        worker.execute(()->{String result;long[] notified={0};try{result=titleOnly?CloudScoreSync.titleOnly(this):CloudScoreSync.run(this,upload,s->{sendBroadcast(new Intent(LocalScoreSync.UPDATED).setPackage(getPackageName()));long now=android.os.SystemClock.elapsedRealtime();if(now-notified[0]>700){notified[0]=now;((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(ID,notification(s));}});}catch(Exception e){result="云端同步未完成，本机成绩保留。"+(e.getMessage()==null?"请重试。":e.getMessage());}
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(ID,notification(result));active=false;sendBroadcast(new Intent(LocalScoreSync.UPDATED).setPackage(getPackageName()));stopForeground(STOP_FOREGROUND_DETACH);stopSelf();});return START_NOT_STICKY;}
    @Override public void onDestroy(){active=false;worker.shutdown();super.onDestroy();}
}
