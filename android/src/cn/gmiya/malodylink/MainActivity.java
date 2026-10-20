package cn.gmiya.malodylink;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int BG=0xff0f172a,CARD=0xff19263b,INK=0xfff1f5f9,MUTED=0xffb1bfd2,GREEN=0xff22c55e;
    private Engine engine;private LinearLayout root;private TextView status;private volatile boolean busy=false;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private long loadedSync;private boolean syncReceiverRegistered;
    private final BroadcastReceiver syncUpdates=new BroadcastReceiver(){public void onReceive(Context c,Intent i){reloadSynced();}};
    private IBinder readerBinder;
    private Shizuku.UserServiceArgs serviceArgs;
    private final Shizuku.OnRequestPermissionResultListener permissionListener=(code,result)->{
        if(result==PackageManager.PERMISSION_GRANTED)bindReader();else message("未获得读取授权。可以使用 USB 同步或手动导入。");
    };
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){readerBinder=binder;message("已连接只读服务，点击「读取本机成绩」开始筛选。");}
        public void onServiceDisconnected(ComponentName name){readerBinder=null;message("读取服务已断开，请重新授权。");}
    };
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        try{engine=new Engine(this);}catch(Exception e){new AlertDialog.Builder(this).setMessage("初始化失败："+e.getMessage()).setPositiveButton("关闭",(d,w)->finish()).show();return;}
        loadedSync=getSharedPreferences("reader",0).getLong("last_sync",0);
        IntentFilter updates=new IntentFilter(LocalScoreSync.UPDATED);if(Build.VERSION.SDK_INT>=33)registerReceiver(syncUpdates,updates,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(syncUpdates,updates);syncReceiverRegistered=true;
        Shizuku.addRequestPermissionResultListener(permissionListener);
        render();if(new File(getFilesDir(),"inbox/manifest.json").exists())runTask(()->"USB 导入完成：新增 "+engine.importUsb()+" 条成绩");
    }
    @Override protected void onResume(){super.onResume();reloadSynced();if(engine!=null&&!busy&&new File(getFilesDir(),"inbox/manifest.json").exists())runTask(()->"USB 导入完成：新增 "+engine.importUsb()+" 条成绩");}
    @Override protected void onDestroy(){if(syncReceiverRegistered)unregisterReceiver(syncUpdates);Shizuku.removeRequestPermissionResultListener(permissionListener);if(serviceArgs!=null&&Shizuku.pingBinder())try{Shizuku.unbindUserService(serviceArgs,connection,true);}catch(Exception ignored){}worker.shutdown();super.onDestroy();}
    private void reloadSynced(){
        long latest=getSharedPreferences("reader",0).getLong("last_sync",0);
        if(engine!=null&&!busy&&!LocalScoreSync.isBusy()&&!LocalSyncService.active&&latest>loadedSync){
            loadedSync=latest;runTask(()->{engine=new Engine(this);return "已更新本机收录成绩。";});
        }
    }
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private void space(LinearLayout l,int h){View v=new View(this);l.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(dp(20),dp(18),dp(20),dp(18));l.setBackground(shape(CARD,18));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(14);root.addView(l,p);return l;}
    private void button(LinearLayout parent,String label,boolean primary,Runnable action){Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextSize(16);b.setTextColor(primary?BG:INK);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary?GREEN:0xff2b3c55));b.setMinHeight(dp(52));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(54));p.topMargin=dp(10);parent.addView(b,p);b.setOnClickListener(v->{if((busy||LocalScoreSync.isBusy()||LocalSyncService.active)&&!label.equals("连接设置 / 首次配对")&&!label.equals("同步本机成绩")&&!label.equals("O20 + N10 成绩图")){message("正在同步，请稍候；可在同步页面或通知中查看进度。");return;}action.run();});}
    private void render(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(22),dp(24),dp(22),dp(30));scroll.addView(root);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{scroll.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
        root.addView(text("MALODY LINK  /  1.0.0",12,GREEN,true));space(root,8);root.addView(text("只收藏你的谱库成绩",27,INK,true));space(root,6);root.addView(text("按服务器谱库目录收录，不在谱库的成绩自动跳过。",14,MUTED,false));space(root,24);
        LinearLayout summary=card();summary.addView(text("已收录成绩",14,MUTED,false));summary.addView(text(Integer.toString(engine.scores.length()),44,INK,true));
        summary.addView(text("有效谱面 "+engine.charts().length()+" 张  ·  目录映射 "+validAliases()+" 张",13,MUTED,false));
        button(summary,"O20 + N10 成绩图",true,()->startActivity(new Intent(this,PosterActivity.class)));
        LinearLayout reader=card();reader.addView(text("读取与筛选",20,INK,true));space(reader,4);reader.addView(text("首次按引导配对；之后点一次同步即可。默认账号自动更新谱库，无需手填账号。",14,MUTED,false));
        button(reader,"同步本机成绩",true,()->startActivityForResult(new Intent(this,InternalReaderActivity.class).putExtra("sync_now",true),12));
        button(reader,"连接设置 / 首次配对",false,()->startActivityForResult(new Intent(this,InternalReaderActivity.class),12));
        button(reader,"Shizuku 兼容读取（可选）",false,()->{if(readerBinder==null)authorize();else scan();});
        if((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0)button(reader,"导入 USB 同步数据",false,()->runTask(()->"USB 导入完成：新增 "+engine.importUsb()+" 条成绩"));
        button(reader,"选择回放文件",false,()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,10);});
        status=text(busy?"正在处理…":"等待操作。成绩尚未上传。",14,GREEN,false);root.addView(status);space(root,16);
        LinearLayout server=card();server.addView(text("我的谱面服务器",18,INK,true));server.addView(text(engine.server,13,MUTED,false));
        long updated=engine.catalog.optLong("updated_at");server.addView(text("清单更新："+date(updated),12,MUTED,false));
        button(server,"更新谱库（默认账号）",false,()->runTask(()->DefaultCatalog.refresh(this,engine,true,this::message)));button(server,"其他服务器 / 账号",false,this::login);button(server,"导出匹配成绩 JSON",false,()->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"malody-link-scores.json");startActivityForResult(i,11);});
        root.addView(text("最近成绩",22,INK,true));space(root,12);
        if(engine.scores.length()==0){LinearLayout empty=card();empty.addView(text("还没有收录成绩",18,INK,true));empty.addView(text("点「同步本机成绩」，按当前步骤完成首次授权；谱库会自动更新。",14,MUTED,false));}
        for(int i=engine.scores.length()-1;i>=Math.max(0,engine.scores.length()-50);i--){JSONObject s=engine.scores.optJSONObject(i);LinearLayout row=card();row.addView(text(s.optString("title"),18,INK,true));row.addView(text(s.optString("version")+"  ·  CID "+s.optInt("cid"),13,GREEN,false));space(row,12);
            row.addView(text(String.format(Locale.ROOT,"%,d",s.optLong("score_candidate")),30,INK,true));row.addView(text(String.format(Locale.ROOT,"准确率 %.5f%%",s.optDouble("accuracy_candidate")),16,MUTED,false));
            space(row,8);row.addView(text("数值字段待核对 · 已按谱库目录匹配",12,0xfffbbf24,false));row.addView(text(s.optString("player")+"  /  "+date(s.optLong("played_at")),12,MUTED,false));}
        space(root,12);root.addView(text("仅保存筛选后的成绩。上传和 QQ 群展示尚未接入；导出文件包含待核对的原始成绩字段。",12,MUTED,false));
    }
    private int validAliases(){int count=0;Iterator<String> it=engine.aliases.keys();while(it.hasNext()){JSONObject a=engine.aliases.optJSONObject(it.next());if(a!=null&&engine.active(a.optInt("cid"),a.optString("server_hash")))count++;}return count;}
    private String date(long seconds){if(seconds<=0)return "未知";SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA);f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));return f.format(new Date(seconds*1000));}
    private void message(String s){runOnUiThread(()->{if(status!=null)status.setText(s);});}
    private interface Task {String run()throws Exception;}
    private void runTask(Task task){if(busy)return;busy=true;message("正在处理，请稍候…");worker.execute(()->{String result;try{result=task.run();}catch(Exception e){result="未完成："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}String done=result;runOnUiThread(()->{busy=false;if(!isFinishing()&&!isDestroyed()){render();message(done);}});});}
    private void authorize(){
        if(!Shizuku.pingBinder()){new AlertDialog.Builder(this).setTitle("需要启动 Shizuku").setMessage("普通 App 无法直接读取 Malody 数据目录。请先安装并启动 Shizuku，再回来授权；也可以继续使用 USB 同步。").setPositiveButton("打开 Shizuku",(d,w)->{Intent i=getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");if(i!=null)startActivity(i);else startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://shizuku.rikka.app/download/")));}).setNegativeButton("稍后",null).show();return;}
        try{if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED)bindReader();else Shizuku.requestPermission(100);}catch(Exception e){message("授权失败："+e.getMessage());}
    }
    private void bindReader(){try{serviceArgs=new Shizuku.UserServiceArgs(new ComponentName(this,ReadOnlyService.class)).daemon(false).processNameSuffix("malody_readonly").version(2);Shizuku.bindUserService(serviceArgs,connection);message("正在连接只读服务…");}catch(Exception e){message("连接失败："+e.getMessage());}}
    private void scan(){if(readerBinder==null||!readerBinder.isBinderAlive()){authorize();return;}Engine.Reader reader=Engine.binderReader(readerBinder);runTask(()->engine.scan(reader,this::message));}
    private EditText input(LinearLayout l,String label,String value,boolean password){l.addView(text(label,14,INK,false));EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(INK);e.setTextSize(16);e.setText(value);e.setInputType(password?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT);l.addView(e,new LinearLayout.LayoutParams(-1,dp(54)));return e;}
    private void login(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(dp(24),dp(12),dp(24),0);EditText address=input(l,"服务器地址",engine.server,false),user=input(l,"账号用户名","",false),pass=input(l,"密码（不保存）","",true);
        l.addView(text("只读取谱面清单和待核验谱面，不向 OpenList 上传成绩。",12,MUTED,false));new AlertDialog.Builder(this).setTitle("连接谱库").setView(l).setNegativeButton("取消",null).setPositiveButton("连接",(d,w)->{String a=address.getText().toString(),u=user.getText().toString(),p=pass.getText().toString();pass.setText("");runTask(()->{engine.connect(a,u,p,this::message);return "谱库已更新："+engine.charts().length()+" 张有效谱面。可开始读取成绩。";});}).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==12){if(result==RESULT_OK)runTask(()->{engine=new Engine(this);return "已更新本机收录成绩。";});return;}if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if(request==10)runTask(()->engine.importReplay(Engine.read(getContentResolver().openInputStream(uri),33554432))?"已收录 1 条匹配成绩":"已跳过：谱面未经核验、不属于当前谱库，或成绩已存在。");
        if(request==11)runTask(()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){out.write(engine.export().toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}return "已导出当前谱库匹配成绩。";});
    }
}
