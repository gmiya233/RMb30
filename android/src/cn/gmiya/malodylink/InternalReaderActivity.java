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
    private TextView status,summary,pairHint,developerHint;private LinearLayout advanced,developerStep,wirelessStep,pairStep,readyStep;private Button recover,pairButton,developerButton,syncButton;
    private boolean wirelessConfirmed;
    private boolean busy,retryOnResume;private long openedAt;private boolean receiverRegistered;
    private AlertDialog developerReturnDialog;
    private static final int GUIDE_NOTICE=31;
    private final int BG=0xff0f172a,INK=0xfff1f5f9,MUTED=0xffbacada;
    private final BroadcastReceiver updates=new BroadcastReceiver(){public void onReceive(Context c,Intent i){refresh();}};
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private TextView label(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(10),0,dp(10));return t;}
    private Button button(LinearLayout root,String text,Runnable action){Button b=new Button(this);b.setText(text);b.setTextSize(16);b.setTextColor(INK);b.setAllCaps(false);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff25465c));b.setMinHeight(dp(52));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);root.addView(b,p);b.setOnClickListener(v->action.run());return b;}
    @Override public void onCreate(Bundle b){super.onCreate(b);openedAt=System.currentTimeMillis();getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);scroll.setFillViewport(true);
        LinearLayout shell=new LinearLayout(this);shell.setGravity(android.view.Gravity.TOP|android.view.Gravity.CENTER_HORIZONTAL);scroll.addView(shell);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(20),dp(16),dp(20),dp(24));
        int width=Math.min(getResources().getDisplayMetrics().widthPixels,dp(640));shell.addView(root,new LinearLayout.LayoutParams(width,-2));setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,in)->{scroll.setPadding(0,in.getSystemWindowInsetTop(),0,in.getSystemWindowInsetBottom());return in;});
        button(root,"返回首页",this::finish);
        root.addView(label("本机成绩同步",26,INK));summary=label("",16,MUTED);root.addView(summary);
        boolean paired=getSharedPreferences("reader",0).getBoolean("paired",false);
        developerStep=section(root);
        developerStep.addView(label("首次设置 · 开启开发者模式",20,INK));
        developerHint=label("进入关于手机 → 版本信息，连续点 7 次版本号。看到已开启的提示后，返回本App继续。",16,MUTED);developerStep.addView(developerHint);
        developerButton=button(developerStep,"前往关于手机",this::deviceInfoSettings);
        wirelessStep=section(root);
        wirelessStep.addView(label("开启无线调试",20,INK));
        wirelessStep.addView(label("先打开右侧开关，再直接点「无线调试」文字或整行空白区域进入详情。右侧开关只负责开启，不是进入按钮。",16,MUTED));
        wirelessStep.addView(new ConnectionGuideView(this,1),new LinearLayout.LayoutParams(-1,-2));
        button(wirelessStep,"前往无线调试",this::settings);
        button(wirelessStep,"已开启，继续",()->{wirelessConfirmed=true;refresh();});
        pairStep=section(root);
        pairStep.addView(label("最后一步 · 完成系统配对",20,INK));
        pairHint=label("点下方按钮打开系统设置并准备配对通知。\n如果看到开发者选项，先直接点「无线调试」文字进入详情，再点「使用配对码配对设备」。",16,MUTED);pairStep.addView(pairHint);
        pairButton=button(pairStep,"开始配对并自动导入",this::startPairing);
        button(pairStep,"开启六位码自动识别（可选）",this::automaticPairingSettings);
        pairStep.addView(label("图 1 · 直接点「无线调试」，不只是开开关",16,INK));
        pairStep.addView(new ConnectionGuideView(this,1),new LinearLayout.LayoutParams(-1,-2));
        pairStep.addView(label("图 2 · 点「使用配对码配对设备」",16,INK));
        pairStep.addView(new ConnectionGuideView(this,2),new LinearLayout.LayoutParams(-1,-2));
        pairStep.addView(label("开启自动识别后，显示六位码并保持窗口打开即可。系统不允许读取时，也可下拉通知手动输入。配对成功后自动读取成绩。",16,MUTED));
        readyStep=section(root);
        readyStep.addView(label("已完成首次授权",20,INK));
        readyStep.addView(label("点一次即可同步。只检查新增或变化的回放；短暂断线会自动重试。",16,MUTED));
        syncButton=button(readyStep,"同步成绩",()->connectAndRead(0));syncButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff22c55e));syncButton.setTextColor(BG);
        status=label("",16,0xff80e6ed);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);root.addView(status);
        recover=button(root,"重试同步",()->connectAndRead(0));recover.setVisibility(View.GONE);
        button(root,"查看 O20 + N10 成绩图",()->startActivity(new Intent(this,PosterActivity.class)));
        button(root,"遇到问题 / 连接选项",()->{advanced.setVisibility(advanced.getVisibility()==View.GONE?View.VISIBLE:View.GONE);});
        advanced=section(root);advanced.setVisibility(View.GONE);
        button(advanced,"打开无线调试设置",()->{retryOnResume=getSharedPreferences("reader",0).getBoolean("paired",false);settings();});
        button(advanced,"进入开发者选项",this::developerSettings);
        button(advanced,"重新配对",this::startPairing);
        button(advanced,"六位码自动识别设置",this::automaticPairingSettings);
        button(advanced,"停用六位码自动识别",()->{getSharedPreferences("reader",0).edit().putBoolean("auto_pair_code",false).apply();PairingService.cancelAutomatic();status.setText("自动识别已停用，仍可在通知中手动输入配对码。");});
        button(advanced,"手动输入连接端口",this::manualConnect);button(advanced,"手动配对 / 分屏输入",this::manualPair);
        button(advanced,"断开读取",()->task(()->{InternalAdb.get(this).disconnect();return "已断开，首次配对信息保留。";}));
        button(advanced,"打开 Malody 6.6.42",()->{Intent launch=getPackageManager().getLaunchIntentForPackage("me.mugzone.emiria");if(launch!=null)startActivity(launch);else status.setText("未找到 Malody 6，请确认已安装。");});
        root.addView(label("仅只读访问 Malody 谱面与回放，不修改游戏数据。首次授权由系统确认。",14,MUTED));
        IntentFilter filter=new IntentFilter(LocalScoreSync.UPDATED);if(Build.VERSION.SDK_INT>=33)registerReceiver(updates,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(updates,filter);receiverRegistered=true;
        refresh();
        if(getIntent().getBooleanExtra("sync_now",false)&&paired)root.post(()->connectAndRead(0));
    }
    private LinearLayout section(LinearLayout root){
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(16),dp(12),dp(16),dp(16));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(0xff19263b);bg.setCornerRadius(dp(16));box.setBackground(bg);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(16);root.addView(box,params);return box;
    }
    private boolean wirelessEnabled(){return Settings.Global.getInt(getContentResolver(),"adb_wifi_enabled",0)==1||wirelessConfirmed;}
    private void displayStage(int stage){
        developerStep.setVisibility(stage==SetupGuide.DEVELOPER?View.VISIBLE:View.GONE);
        wirelessStep.setVisibility(stage==SetupGuide.WIRELESS?View.VISIBLE:View.GONE);
        pairStep.setVisibility(stage==SetupGuide.PAIR?View.VISIBLE:View.GONE);
        readyStep.setVisibility(stage==SetupGuide.READY?View.VISIBLE:View.GONE);
        summary.setText(stage==SetupGuide.READY?"已保留配对记录，无需重复设置。":stage==SetupGuide.PAIR?"仅首次需要系统授权。配对成功后自动导入。":stage==SetupGuide.WIRELESS?"完成这一步后返回，App会继续下一步。":"首次需要一次系统授权，之后点一次同步即可。");
    }
    private void refresh(){if(status==null||isDestroyed())return;android.content.SharedPreferences p=getSharedPreferences("reader",0);boolean paired=p.getBoolean("paired",false);
        int stage=SetupGuide.stage(!developerDisabled(),DeveloperModeState.masterEnabled(this),wirelessEnabled(),paired);
        displayStage(stage);
        boolean syncing=busy||LocalScoreSync.isBusy()||LocalSyncService.active;
        syncButton.setEnabled(!syncing);pairButton.setEnabled(!syncing);
        if(busy)return;
        if(LocalScoreSync.isBusy()||LocalSyncService.active){status.setText(LocalScoreSync.currentProgress.isEmpty()?"正在准备同步…":LocalScoreSync.currentProgress);recover.setVisibility(View.GONE);return;}
        if(PairingService.active){status.setText(PairingService.phase);return;}
        status.setText(p.getString("last_result",paired?"已准备好，点击同步成绩。":"按上方说明完成当前步骤。"));
        recover.setVisibility(stage==SetupGuide.READY&&p.contains("last_ok")&&!p.getBoolean("last_ok",false)?View.VISIBLE:View.GONE);
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
        // Tablet details live in a non-exported host; the phone route can silently return.
        if(Build.MANUFACTURER.equalsIgnoreCase("vivo")&&getResources().getConfiguration().smallestScreenWidthDp>=600){developerSettings();return;}
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
        try{startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).putExtra(":settings:fragment_args_key","toggle_adb_wireless"));status.setText("直接点「无线调试」文字或整行空白处进入详情，右侧开关只负责开启。然后点「使用配对码配对设备」，保持弹窗打开，在通知中输入六位码。");}
        catch(Exception unavailable){try{startActivity(new Intent(Settings.ACTION_SETTINGS));status.setText("请进入系统设置，搜索「无线调试」；若没有结果，需先开启开发者选项。");}catch(Exception e){status.setText("无法启动系统设置，请从手机设置进入开发者选项 → 无线调试。");}}
    }

    private void startPairing(){
        if(developerDisabled()){deviceInfoSettings();return;}
        if(!DeveloperModeState.masterEnabled(this)){developerSettings();return;}
        if(busy||LocalScoreSync.isBusy()||LocalSyncService.active){status.setText("同步正在进行；结束后可从连接选项重新配对。");return;}
        if(Build.VERSION.SDK_INT<30){status.setText("本机没有Android 11的系统配对入口，可用原有USB或Shizuku兼容读取。");return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},40);return;}
        startForegroundService(new Intent(this,PairingService.class).setAction("START_AND_SYNC"));
        status.setText(PairCodeAccessibilityService.automatic(this)?"请打开六位码弹窗，等待自动识别；无需输入。若系统未提供文本，仍可通知手动输入。":"请点「使用配对码配对设备」，保持窗口打开，下拉通知输入六位码；配对后将自动同步。");settings();
    }
    private void automaticPairingSettings(){
        new AlertDialog.Builder(this).setTitle("启用配对码自动识别").setMessage("需要你在系统辅助功能中手动开启「Malody Link 配对码自动识别」。\n\n仅在你点开始配对后的3分钟内，读取系统设置中六位配对码并自动提交到本机。不截图、不保存、不上传；不会自动点击系统授权按钮。\n\n开启后返回App，再点开始配对。若系统限制了文本读取，保留通知手动输入。").setNegativeButton("取消",null).setPositiveButton("前往系统开启",(d,w)->{getSharedPreferences("reader",0).edit().putBoolean("auto_pair_code",true).apply();try{PairCodeAccessibilityService.settings(this);}catch(Exception e){status.setText("请在手机设置 → 辅助功能中开启 Malody Link 配对码自动识别。");}}).show();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==40){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startPairing();else status.setText("通知内输入配对码需要通知权限；也可在连接选项中使用分屏手动配对。");}}
    private interface Work {String run()throws Exception;}
    private void task(Work action){if(busy||LocalScoreSync.isBusy()||LocalSyncService.active){status.setText("正在同步，请稍候。可在通知中查看进度。");return;}busy=true;status.setText("正在处理…");recover.setVisibility(View.GONE);
        worker.execute(()->{String result;boolean ok;try{result=action.run();ok=true;}catch(Exception e){result=LocalScoreSync.friendly(e);ok=false;}String done=result;boolean success=ok;
            runOnUiThread(()->{busy=false;if(!isDestroyed()){status.setText(done);recover.setVisibility(success?View.GONE:View.VISIBLE);if(success)setResult(RESULT_OK,new Intent());}});});
    }
    private void connectAndRead(int port){
        if(developerDisabled()||!DeveloperModeState.masterEnabled(this)){refresh();return;}
        try{LocalSyncService.request(this,port);refresh();}catch(Exception e){status.setText(LocalScoreSync.friendly(e));}
    }
    private EditText field(LinearLayout form,String label,boolean code){form.addView(label(label,14,INK));EditText e=new EditText(this);e.setTextColor(INK);e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_NUMBER|(code?InputType.TYPE_NUMBER_VARIATION_PASSWORD:0));if(code){e.setSaveEnabled(false);e.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);}form.addView(e);return e;}
    private LinearLayout form(){LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(dp(20),0,dp(20),0);return form;}
    private void manualConnect(){LinearLayout form=form();form.addView(label("输入无线调试首页「IP地址和端口」冒号后的连接端口，配对端口与它不同。",14,MUTED));EditText port=field(form,"连接端口",false);new AlertDialog.Builder(this).setTitle("本机连接").setView(form).setNegativeButton("取消",null).setPositiveButton("连接读取",(d,w)->{try{connectAndRead(Integer.parseInt(port.getText().toString()));}catch(Exception e){status.setText("请输入正确的连接端口");}}).show();}
    private void manualPair(){LinearLayout form=form();form.addView(label("将本App与系统设置分屏，保持「使用配对码配对设备」窗口打开，输入窗口里的配对端口和六位配对码。",14,MUTED));EditText port=field(form,"配对端口",false),code=field(form,"六位配对码",true);new AlertDialog.Builder(this).setTitle("本机配对").setView(form).setNegativeButton("取消",null).setPositiveButton("配对",(d,w)->{String entered=code.getText().toString();code.setText("");try{int pairPort=Integer.parseInt(port.getText().toString());task(()->{if(!InternalAdb.get(this).pairLocal(pairPort,entered))throw new Exception("配对未成功，请使用新的配对码");getSharedPreferences("reader",0).edit().putBoolean("paired",true).apply();LocalSyncService.request(this,0);return "配对成功，正在自动导入…";});}catch(Exception e){status.setText("请输入正确的配对端口");}}).show();}

    @Override public void onDestroy(){if(receiverRegistered)unregisterReceiver(updates);worker.shutdown();super.onDestroy();}
}
