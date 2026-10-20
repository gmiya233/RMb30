package cn.gmiya.malodylink;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.util.concurrent.*;

public final class InternalReaderActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status,summary,pairHint,developerHint;private LinearLayout advanced;private Button recover,pairButton,developerButton;
    private boolean busy,retryOnResume;private long openedAt;private boolean receiverRegistered;
    private AlertDialog developerReturnDialog;
    private static final int GUIDE_NOTICE=31;
    private final int BG=0xff0f172a,INK=0xfff1f5f9,MUTED=0xffbacada;
    private final BroadcastReceiver updates=new BroadcastReceiver(){public void onReceive(Context c,Intent i){refresh();}};
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView label(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(10),0,dp(10));return t;}
    private Button button(LinearLayout root,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setTextSize(16);b.setTextColor(INK);b.setAllCaps(false);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff25465c));b.setMinHeight(dp(52));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);root.addView(b,p);b.setOnClickListener(v->action.run());return b;}
    @Override public void onCreate(Bundle b){super.onCreate(b);openedAt=System.currentTimeMillis();getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(20),dp(14),dp(20),dp(20));scroll.addView(root);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,in)->{scroll.setPadding(0,in.getSystemWindowInsetTop(),0,in.getSystemWindowInsetBottom());return in;});
        TextView back=label("‹ 返回",16,0xff80e6ed);back.setMinHeight(dp(48));back.setOnClickListener(v->finish());root.addView(back);
        root.addView(label("本机成绩同步",26,INK));summary=label("",15,MUTED);root.addView(summary);
        boolean paired=getSharedPreferences("reader",0).getBoolean("paired",false);
        developerButton=button(root,"1. 开启开发者选项",()->{if(developerDisabled())deviceInfoSettings();else developerSettings();});
        developerHint=label("",14,MUTED);root.addView(developerHint);
        button(root,"2. 打开系统无线调试",this::settings);
        root.addView(label("在系统页面开启无线调试，然后返回这里继续第 3 步。",14,MUTED));
        button(root,"打不开？打开开发者选项",this::developerSettings);
        pairButton=button(root,"3. 配对设备（仅首次）",this::startPairing);
        pairHint=label("",14,MUTED);root.addView(pairHint);
        Button sync=button(root,"4. 连接并同步成绩",()->{if(getSharedPreferences("reader",0).getBoolean("paired",false))connectAndRead(0);else status.setText("请先完成第 3 步配对。配对成功后会自动同步成绩。");});sync.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff22c55e));sync.setTextColor(BG);
        status=label("",15,0xff80e6ed);root.addView(status);
        recover=button(root,"打开无线调试，返回后自动重试",()->{retryOnResume=true;settings();});recover.setVisibility(View.GONE);
        button(root,"查看 O20 + N10 成绩图",()->startActivity(new Intent(this,PosterActivity.class)));
        button(root,"打开 Malody",()->{Intent launch=getPackageManager().getLaunchIntentForPackage("me.mugzone.emiria");if(launch!=null)startActivity(launch);else status.setText("未找到 Malody，请确认已安装。");});
        button(root,"连接选项",()->advanced.setVisibility(advanced.getVisibility()==View.GONE?View.VISIBLE:View.GONE));
        advanced=new LinearLayout(this);advanced.setOrientation(1);advanced.setVisibility(View.GONE);root.addView(advanced);
        button(advanced,"手动输入连接端口",this::manualConnect);button(advanced,"手动配对 / 分屏输入",this::manualPair);
        button(advanced,"断开读取",()->task(()->{InternalAdb.get(this).disconnect();return "已断开，首次配对信息保留。";}));
        root.addView(label("只读导入 Malody 成绩文件，按服务器目录筛选歌曲。谱库通过默认只读账号自动更新，无需额外安装 Shizuku。",12,MUTED));
        IntentFilter filter=new IntentFilter(LocalScoreSync.UPDATED);if(Build.VERSION.SDK_INT>=33)registerReceiver(updates,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(updates,filter);receiverRegistered=true;
        refresh();
        if(getIntent().getBooleanExtra("sync_now",false)&&paired)root.post(()->connectAndRead(0));
    }
    private void refresh(){if(status==null||isDestroyed())return;android.content.SharedPreferences p=getSharedPreferences("reader",0);boolean paired=p.getBoolean("paired",false);
        boolean disabled=developerDisabled();developerButton.setText(disabled?"1. 开启开发者模式":"1. 进入开发者选项");
        developerHint.setText(disabled?"首次需在版本信息中连续点 7 次版本号。看到开启提示后，请返回本App；按钮会自动变为「进入开发者选项」。":"已检测到开发者模式，无需再次连点版本号。点上方按钮进入开发者选项，找到无线调试；页面顶部如有总开关，请开启。");
        pairButton.setText(paired?"3. 配对设备（已有记录，可跳过）":"3. 配对设备（仅首次）");
        pairHint.setText(paired?"已有配对记录，通常不用再输入六位码。授权失效时可点此重新配对。":"点此允许通知，打开系统的「使用配对码配对设备」，保持弹窗打开，下拉通知输入六位码；成功后自动执行第 4 步。");
        summary.setText(disabled?"先完成第 1 步，开启后返回本App继续。":"首次按 1 → 2 → 3 → 4 操作。已开启开发者模式，后续无需重复连点版本号。");
        if(busy)return;
        if(PairingService.active){status.setText(PairingService.phase);return;}
        if(LocalScoreSync.isBusy()){status.setText("正在同步成绩…");return;}
        status.setText(p.getString("last_result",paired?"已准备好。请保持无线调试开启。":"从第 1 步开始，开启并进入开发者选项。"));
        recover.setVisibility(p.contains("last_ok")&&!p.getBoolean("last_ok",false)?View.VISIBLE:View.GONE);
        if(p.getLong("last_sync",0)>=openedAt)setResult(RESULT_OK,new Intent());
    }
    @Override protected void onResume(){super.onResume();refresh();
        if(getSharedPreferences("reader",0).getBoolean("developer_guide_pending",false)&&!developerDisabled()){
            getSharedPreferences("reader",0).edit().putBoolean("developer_guide_pending",false).apply();((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(GUIDE_NOTICE);
            developerReturnDialog=new AlertDialog.Builder(this).setTitle("开发者模式已开启").setMessage("无需再连点版本号。现在可以从App进入开发者选项，开启页面顶部的总开关，再找到「无线调试」。").setNegativeButton("稍后",null).setPositiveButton("进入开发者选项",(d,w)->developerSettings()).show();
        }
        if(retryOnResume&&!busy){retryOnResume=false;new Handler(Looper.getMainLooper()).post(()->connectAndRead(0));}}
    private void settings(){
        if(developerDisabled()){deviceInfoSettings();return;}
        if(!DeveloperModeState.masterEnabled(this)){status.setText("已开启开发者模式。请在开发者选项页面开启顶部总开关，再找到无线调试。");developerSettings();return;}
        if(Build.MANUFACTURER.equalsIgnoreCase("vivo")||Build.BRAND.equalsIgnoreCase("iqoo"))try{
            startActivity(new Intent().setComponent(new ComponentName("com.android.settings","com.vivo.settings.VivoSubSettings")).putExtra(":settings:show_fragment","com.android.settings.development.WirelessDebuggingFragment"));return;
        }catch(Exception ignored){}
        try{startActivity(new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"));return;}catch(Exception ignored){}
        try{startActivity(new Intent().setComponent(new ComponentName("com.android.settings","com.android.settings.Settings$WirelessDebuggingActivity")));return;}catch(Exception ignored){}
        developerSettings();
    }
    private boolean developerDisabled(){return !DeveloperModeState.unlocked(this);}
    private void deviceInfoSettings(){
        if(!developerDisabled()){developerSettings();return;}
        new AlertDialog.Builder(this).setTitle("开启后，请返回本App").setMessage("进入关于手机 → 版本信息，连续点 7 次「软件版本号」或「版本号」。看到系统提示已开启后，请按返回或切回 Malody Link，再点「进入开发者选项」。此操作仅首次需要。").setNegativeButton("取消",null).setPositiveButton("前往版本信息",(d,w)->{
            getSharedPreferences("reader",0).edit().putBoolean("developer_guide_pending",true).apply();showDeveloperReturnNotice();
            status.setText("看到系统开启提示后，请返回本App，再进入开发者选项。");
            try{startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));}catch(Exception unavailable){try{startActivity(new Intent(Settings.ACTION_SETTINGS));}catch(Exception e){status.setText("请手动打开系统设置 → 关于手机 → 版本信息，完成后返回本App。");}}
        }).show();
    }
    private void showDeveloperReturnNotice(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);String channel="local_reader_setup";manager.createNotificationChannel(new NotificationChannel(channel,"首次连接操作提示",NotificationManager.IMPORTANCE_DEFAULT));
        Intent back=new Intent(this,InternalReaderActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open=PendingIntent.getActivity(this,31,back,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        manager.notify(GUIDE_NOTICE,new Notification.Builder(this,channel).setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("完成版本号操作后，点这里返回App").setContentText("返回 Malody Link 后，将检测开发者模式并提供跳转入口。").setContentIntent(open).setAutoCancel(true).build());
    }
    private void developerSettings(){
        if(developerDisabled()){deviceInfoSettings();return;}
        try{startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key","toggle_adb_wireless"));status.setText("在开发者选项找到「无线调试」，开启开关；点文字区域进入配对详情。返回后继续下一步。");}
        catch(Exception unavailable){try{startActivity(new Intent(Settings.ACTION_SETTINGS));status.setText("请进入系统设置，搜索「无线调试」；若没有结果，需先开启开发者选项。");}catch(Exception e){status.setText("无法启动系统设置，请从手机设置进入开发者选项 → 无线调试。");}}
    }

    private void startPairing(){
        if(developerDisabled()){deviceInfoSettings();return;}
        if(!DeveloperModeState.masterEnabled(this)){developerSettings();return;}
        if(busy||LocalScoreSync.isBusy()){status.setText("同步正在进行；仍可通过第 2 步打开系统设置。同步结束后再重新配对。");return;}
        if(Build.VERSION.SDK_INT<30){status.setText("本机没有Android 11的系统配对入口，可用原有USB或Shizuku兼容读取。");return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},40);return;}
        if(PairingService.active){settings();return;}
        startForegroundService(new Intent(this,PairingService.class).setAction("START_AND_SYNC"));
        status.setText("请点「使用配对码配对设备」，保持窗口打开，下拉通知输入六位码；配对后将自动同步。");settings();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==40){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startPairing();else status.setText("通知内输入配对码需要通知权限；也可在连接选项中使用分屏手动配对。");}}
    private interface Work {String run()throws Exception;}
    private void task(Work action){if(busy||LocalScoreSync.isBusy()){status.setText("正在同步，请稍候；第 2 步系统设置仍可打开。");return;}busy=true;status.setText("正在处理…");recover.setVisibility(View.GONE);
        worker.execute(()->{String result;boolean ok;try{result=action.run();ok=true;}catch(Exception e){result=LocalScoreSync.friendly(e);ok=false;}String done=result;boolean success=ok;
            runOnUiThread(()->{busy=false;if(!isDestroyed()){status.setText(done);recover.setVisibility(success?View.GONE:View.VISIBLE);if(success)setResult(RESULT_OK,new Intent());}});});
    }
    private void connectAndRead(int port){if(developerDisabled()){status.setText("请先完成第 1 步，开启开发者模式。");return;}if(!DeveloperModeState.masterEnabled(this)){status.setText("已检测到开发者模式。请先点第 1 步进入开发者选项，开启顶部总开关和无线调试。");return;}task(()->LocalScoreSync.run(this,port,p->runOnUiThread(()->{if(!isDestroyed())status.setText(p); })));}
    private EditText field(LinearLayout form,String label,boolean code){form.addView(label(label,14,INK));EditText e=new EditText(this);e.setTextColor(INK);e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_NUMBER|(code?InputType.TYPE_NUMBER_VARIATION_PASSWORD:0));if(code){e.setSaveEnabled(false);e.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);}form.addView(e);return e;}
    private LinearLayout form(){LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(dp(20),0,dp(20),0);return form;}
    private void manualConnect(){LinearLayout form=form();form.addView(label("输入无线调试首页「IP地址和端口」冒号后的连接端口，配对端口与它不同。",14,MUTED));EditText port=field(form,"连接端口",false);new AlertDialog.Builder(this).setTitle("本机连接").setView(form).setNegativeButton("取消",null).setPositiveButton("连接读取",(d,w)->{try{connectAndRead(Integer.parseInt(port.getText().toString()));}catch(Exception e){status.setText("请输入正确的连接端口");}}).show();}
    private void manualPair(){LinearLayout form=form();form.addView(label("将本App与系统设置分屏，保持「使用配对码配对设备」窗口打开，输入窗口里的配对端口和六位配对码。",14,MUTED));EditText port=field(form,"配对端口",false),code=field(form,"六位配对码",true);new AlertDialog.Builder(this).setTitle("本机配对").setView(form).setNegativeButton("取消",null).setPositiveButton("配对",(d,w)->{String entered=code.getText().toString();code.setText("");try{int pairPort=Integer.parseInt(port.getText().toString());task(()->{if(!InternalAdb.get(this).pairLocal(pairPort,entered))throw new Exception("配对未成功，请使用新的配对码");getSharedPreferences("reader",0).edit().putBoolean("paired",true).apply();return LocalScoreSync.run(this,0,progress->runOnUiThread(()->status.setText(progress)));});}catch(Exception e){status.setText("请输入正确的配对端口");}}).show();}

    @Override public void onDestroy(){if(receiverRegistered)unregisterReceiver(updates);worker.shutdown();super.onDestroy();}
}
