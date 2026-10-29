package cn.gmiya.malodylink;

import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
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
    private static final int BG=AppUi.BG,CARD=AppUi.CARD,INK=AppUi.INK,MUTED=AppUi.MUTED,GREEN=AppUi.ACCENT;
    private int selectedTab;private final int[] scrollPositions=new int[5];private ScrollView currentScroll;private LinearLayout screen;private PosterPanel posterPanel;private Bitmap homeImage;private int renderGeneration;private String lastMessage="";
    private RankingHub rankingPanel;private boolean rankSingle;private int rankCid;private String songQuery="",rankQuery="";private int songSearchMode;
    private final ExecutorService previews=Executors.newSingleThreadExecutor();
    private Engine engine;private LinearLayout root;private TextView status;private volatile boolean busy=false;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private long loadedSync,loadedCloud;private boolean syncReceiverRegistered;private TextView cloudStatus;
    private final BroadcastReceiver syncUpdates=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(cloudStatus!=null)cloudStatus.setText(CloudSyncService.active?LocalScoreSync.currentProgress:getSharedPreferences("cloud",0).getString("last_result","尚未同步云端"));reloadSynced();}};
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
        loadedCloud=getSharedPreferences("cloud",0).getLong("last_sync",0);
        IntentFilter updates=new IntentFilter(LocalScoreSync.UPDATED);if(Build.VERSION.SDK_INT>=33)registerReceiver(syncUpdates,updates,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(syncUpdates,updates);syncReceiverRegistered=true;
        Shizuku.addRequestPermissionResultListener(permissionListener);
        selectedTab=state==null?Math.max(0,Math.min(4,getIntent().getIntExtra("tab",0))):Math.max(0,Math.min(4,state.getInt("tab",0)));if(state!=null){rankSingle=state.getBoolean("rankSingle");rankCid=state.getInt("rankCid");songQuery=state.getString("songQuery","");songSearchMode=state.getInt("songSearchMode",0);rankQuery=state.getString("rankQuery","");}render();if(new File(getFilesDir(),"inbox/manifest.json").exists())runTask(()->saveAndCloud(()->"USB 导入完成：新增 "+engine.importUsb()+" 条成绩"));
    }
    @Override protected void onResume(){super.onResume();reloadSynced();if(engine!=null&&!busy&&new File(getFilesDir(),"inbox/manifest.json").exists())runTask(()->saveAndCloud(()->"USB 导入完成：新增 "+engine.importUsb()+" 条成绩"));}
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putInt("tab",selectedTab);state.putBoolean("rankSingle",rankSingle);state.putInt("rankCid",rankCid);state.putString("songQuery",songQuery);state.putInt("songSearchMode",songSearchMode);state.putString("rankQuery",rankQuery);}
    @Override public void onBackPressed(){if(selectedTab==2&&rankingPanel!=null&&rankingPanel.back())return;if(selectedTab!=0)selectTab(0);else super.onBackPressed();}
    @Override public void onRequestPermissionsResult(int req,String[] p,int[] r){super.onRequestPermissionsResult(req,p,r);if(posterPanel!=null)posterPanel.onRequestPermissionsResult(req,p,r);}
    @Override protected void onDestroy(){renderGeneration++;if(posterPanel!=null)posterPanel.close();if(rankingPanel!=null)rankingPanel.close();previews.shutdown();if(syncReceiverRegistered)unregisterReceiver(syncUpdates);Shizuku.removeRequestPermissionResultListener(permissionListener);if(serviceArgs!=null&&Shizuku.pingBinder())try{Shizuku.unbindUserService(serviceArgs,connection,true);}catch(Exception ignored){}worker.shutdown();super.onDestroy();}
    private void reloadSynced(){
        long latest=getSharedPreferences("reader",0).getLong("last_sync",0),cloud=getSharedPreferences("cloud",0).getLong("last_sync",0);
        if(engine!=null&&!busy&&!LocalScoreSync.isBusy()&&!LocalSyncService.active&&!CloudSyncService.active&&(latest>loadedSync||cloud>loadedCloud)){
            loadedSync=latest;loadedCloud=cloud;runTask(()->{engine=new Engine(this);return "已更新收录成绩。";});
        }
    }
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private void space(LinearLayout l,int h){View v=new View(this);l.addView(v,new LinearLayout.LayoutParams(1,dp(h)));}
    private LinearLayout card(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(dp(20),dp(18),dp(20),dp(18));l.setBackground(shape(CARD,18));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(14);root.addView(l,p);return l;}
    private void button(LinearLayout parent,String label,boolean primary,Runnable action){
        boolean compact=selectedTab==4;
        if(label.equals("同步本机成绩")){
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);TextView caption=text(label,compact?14:16,INK,true);row.addView(caption,new LinearLayout.LayoutParams(0,-2,1));
            ImageButton icon=new ImageButton(this);icon.setImageDrawable(new NavIcon(4,primary?AppUi.ON_SUCCESS:INK));int padding=dp(compact?12:14);icon.setPadding(padding,padding,padding,padding);icon.setScaleType(ImageView.ScaleType.FIT_CENTER);icon.setContentDescription(label);icon.setTooltipText(label);icon.setBackground(new RippleDrawable(ColorStateList.valueOf(0x554a8c97),shape(primary?GREEN:AppUi.SOFT,16),shape(Color.WHITE,16)));
            row.addView(icon,new LinearLayout.LayoutParams(dp(compact?48:56),dp(compact?48:56)));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.topMargin=dp(compact?6:12);parent.addView(row,rp);icon.setOnClickListener(v->action.run());return;
        }
        Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextSize(compact?14:16);b.setTextColor(primary?AppUi.ON_SUCCESS:INK);b.setBackgroundTintList(ColorStateList.valueOf(primary?GREEN:AppUi.SOFT));b.setMinHeight(dp(compact?48:52));
        if(compact){b.setMinimumHeight(dp(48));b.setPadding(dp(10),dp(6),dp(10),dp(6));}
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,-2);size.topMargin=dp(compact?4:10);parent.addView(b,size);
        b.setOnClickListener(v->{if((busy||LocalScoreSync.isBusy()||LocalSyncService.active||CloudSyncService.active)&&!label.equals("连接设置 / 首次配对")&&!label.equals("同步本机成绩")&&!label.equals("O20 + N10 成绩图")){message("正在同步，请稍候；可在同步页面或通知中查看进度。");return;}action.run();});
    }
    private LinearLayout settingsCard(LinearLayout parent){
        LinearLayout card=AppUi.column(this,14);card.setBackground(shape(CARD,18));LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,-2);size.bottomMargin=dp(12);parent.addView(card,size);return card;
    }
    private void pairingEntry(){
        LinearLayout card=AppUi.column(this,16);GradientDrawable background=shape(AppUi.SELECTED,18);background.setStroke(dp(1),GREEN);card.setBackground(background);
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);LinearLayout copy=AppUi.column(this,0);copy.addView(text("首次使用，先完成连接设置",18,GREEN,true));copy.addView(text("先开启开发者模式，再开启无线调试并配对。",13,MUTED,false));row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        Button pair=AppUi.button(this,"首次配对 →",()->startActivityForResult(new Intent(this,InternalReaderActivity.class),12));pair.setTextColor(AppUi.ON_SUCCESS);pair.setBackgroundTintList(ColorStateList.valueOf(GREEN));pair.setPadding(dp(14),dp(6),dp(14),dp(6));LinearLayout.LayoutParams action=new LinearLayout.LayoutParams(-2,-2);action.leftMargin=dp(12);row.addView(pair,action);card.addView(row);
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,-2);size.bottomMargin=dp(12);root.addView(card,size);
    }
    private void rememberScroll(){if(currentScroll!=null)scrollPositions[selectedTab]=currentScroll.getScrollY();}
    private void selectTab(int tab){if(tab==selectedTab)return;rememberScroll();currentScroll=null;selectedTab=tab;render();}
    private void render(){
        rememberScroll();renderGeneration++;cloudStatus=null;status=null;
        if(posterPanel!=null){posterPanel.close();posterPanel=null;}
        if(rankingPanel!=null){rankingPanel.close();rankingPanel=null;}
        // Bitmaps displayed by an old detached view are reclaimed by Android, never recycled during drawing.
        homeImage=null;
        screen=new LinearLayout(this);screen.setOrientation(1);screen.setBackgroundColor(BG);setContentView(screen);
        screen.setOnApplyWindowInsetsListener((v,in)->{screen.setPadding(in.getSystemWindowInsetLeft(),in.getSystemWindowInsetTop(),in.getSystemWindowInsetRight(),in.getSystemWindowInsetBottom());return in;});
        FrameLayout content=new FrameLayout(this);content.setBackground(new CharacterBackdrop(this,selectedTab));screen.addView(content,new LinearLayout.LayoutParams(-1,0,1));currentScroll=null;
        if(selectedTab==1){posterPanel=new PosterPanel(this,false);content.addView(posterPanel.view(),new FrameLayout.LayoutParams(-1,-1));}
        else if(selectedTab==2){rankingPanel=new RankingHub(this,engine,rankSingle,rankCid,rankQuery,(single,cid)->{rankSingle=single;rankCid=cid;},q->rankQuery=q);content.addView(rankingPanel,new FrameLayout.LayoutParams(-1,-1));}
        else if(selectedTab==3){content.addView(new SongPanel(this,engine,songQuery,songSearchMode,(mode,q)->{songSearchMode=mode;songQuery=q;},(song,cid)->{rankSingle=true;rankCid=cid;selectTab(2);},false),new FrameLayout.LayoutParams(-1,-1));}
        else{
            currentScroll=new ScrollView(this);currentScroll.setFillViewport(true);currentScroll.setClipToPadding(false);
            root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(20),dp(24),dp(20),dp(24));
            FrameLayout column=new FrameLayout(this){@Override protected void onMeasure(int w,int h){if(getChildCount()>0)getChildAt(0).getLayoutParams().width=Math.min(View.MeasureSpec.getSize(w),dp(760));super.onMeasure(w,h);}};FrameLayout.LayoutParams width=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);column.addView(root,width);currentScroll.addView(column);content.addView(currentScroll,new FrameLayout.LayoutParams(-1,-1));
            if(selectedTab==0)renderHome();else renderSettings();
            final ScrollView scroll=currentScroll;final int position=scrollPositions[selectedTab];scroll.post(()->scroll.scrollTo(0,position));
        }
        View line=new View(this);line.setBackgroundColor(AppUi.BORDER);screen.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout nav=new LinearLayout(this);nav.setId(0x1020100);nav.setPadding(dp(8),dp(6),dp(8),dp(6));nav.setBackgroundColor(AppUi.CARD);nav.setGravity(Gravity.CENTER);
        String[] labels={"首页","B30","排行榜","歌曲","设置"};int[] icons={0,1,9,10,2};for(int i=0;i<labels.length;i++){final int tab=i;boolean active=i==selectedTab;Button item=new Button(this);item.setId(0x1020101+i);item.setText(labels[i]);item.setTextSize(12);item.setAllCaps(false);item.setTextColor(active?GREEN:MUTED);item.setTypeface(Typeface.DEFAULT,active?Typeface.BOLD:Typeface.NORMAL);item.setSelected(active);item.setMinWidth(0);item.setMinimumWidth(0);item.setMinHeight(dp(64));item.setPadding(dp(2),dp(8),dp(2),dp(8));item.setMaxLines(1);item.setAutoSizeTextTypeUniformWithConfiguration(9,12,1,android.util.TypedValue.COMPLEX_UNIT_SP);
            NavIcon icon=new NavIcon(icons[i],active?GREEN:MUTED);icon.setBounds(0,0,dp(23),dp(23));item.setCompoundDrawables(null,icon,null,null);item.setCompoundDrawablePadding(dp(4));item.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24087f8c),shape(active?AppUi.SELECTED:0x00000000,16),shape(Color.WHITE,16)));item.setContentDescription(labels[i]+(active?"，当前页面":""));LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,-2,1);np.setMargins(dp(2),0,dp(2),0);nav.addView(item,np);item.setOnClickListener(v->selectTab(tab));}
        screen.addView(nav,new LinearLayout.LayoutParams(-1,-2));screen.requestApplyInsets();
    }
    private void heading(String title,String subtitle){root.addView(text("MALODY LINK",12,GREEN,true));space(root,8);root.addView(text(title,28,INK,true));space(root,4);root.addView(text(subtitle,14,MUTED,false));space(root,24);}
    private void renderHome(){
        root.setPadding(dp(16),dp(16),dp(16),dp(20));String qq=ScoreStore.profile(this);root.addView(text("MALODY LINK",11,GREEN,true));space(root,4);root.addView(text("我的节奏",22,INK,true));space(root,14);
        LinearLayout overview=card();overview.setPadding(dp(18),dp(16),dp(18),dp(16));overview.setBackground(shape(GREEN,20));
        LinearLayout identity=new LinearLayout(this);identity.setGravity(Gravity.CENTER_VERTICAL);ImageView avatar=new ImageView(this);avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);avatar.setBackground(shape(AppUi.SELECTED,12));avatar.setClipToOutline(true);Bitmap portrait=LocalPlayerProfile.avatarBitmap(this);if(portrait!=null)avatar.setImageBitmap(portrait);else{avatar.setImageResource(android.R.drawable.ic_menu_myplaces);avatar.setColorFilter(GREEN);avatar.setPadding(dp(8),dp(8),dp(8),dp(8));}avatar.setContentDescription("设置本地头像与昵称");avatar.setOnClickListener(v->startActivityForResult(new Intent(this,PlayerProfileActivity.class),20));identity.addView(avatar,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout who=AppUi.column(this,0);LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.leftMargin=dp(12);identity.addView(who,wp);TextView title=text(LocalPlayerProfile.DEFAULT_TITLE,12,0xffd5efec,false);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);who.addView(title);TextView name=text(LocalPlayerProfile.nickname(this,"PLAYER"),18,Color.WHITE,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);who.addView(name);overview.addView(identity);space(overview,12);
        overview.addView(text("PLAYER RATING",11,0xffd5efec,true));TextView rating=text("—",34,Color.WHITE,true);rating.setSingleLine(true);rating.setAutoSizeTextTypeUniformWithConfiguration(24,34,1,android.util.TypedValue.COMPLEX_UNIT_SP);overview.addView(rating);
        TextView breakdown=text("O20 + N10 · 缺位按0计",12,0xffd5efec,false);overview.addView(breakdown);
        ExtraBoards.home(this,engine,root);
        LinearLayout sync=card();sync.setPadding(dp(14),dp(6),dp(14),dp(10));LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);actions.addView(text("本机成绩 · "+engine.scores.length()+"条",14,INK,true),new LinearLayout.LayoutParams(0,-2,1));ImageButton syncButton=new ImageButton(this);syncButton.setImageDrawable(new NavIcon(4,GREEN));syncButton.setContentDescription("同步本机成绩");syncButton.setTooltipText("同步本机成绩");syncButton.setPadding(dp(11),dp(11),dp(11),dp(11));syncButton.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24087f8c),shape(AppUi.SELECTED,12),null));syncButton.setOnClickListener(v->startActivityForResult(new Intent(this,InternalReaderActivity.class).putExtra("sync_now",true),12));actions.addView(syncButton,new LinearLayout.LayoutParams(dp(44),dp(44)));sync.addView(actions);
        status=text(lastMessage.isEmpty()?(qq.isEmpty()?"设置QQ后可同步云存档":getSharedPreferences("cloud",0).getString("last_result","等待同步")):lastMessage,12,MUTED,false);status.setMaxLines(1);status.setEllipsize(android.text.TextUtils.TruncateAt.END);sync.addView(status);status.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("同步状态").setMessage(status.getText()).setPositiveButton("知道了",null).show());
        LinearLayout label=new LinearLayout(this);label.setGravity(Gravity.CENTER_VERTICAL);label.addView(text("我的 B30",18,INK,true),new LinearLayout.LayoutParams(0,-2,1));Button open=new Button(this);open.setText("查看完整图");open.setAllCaps(false);open.setTextSize(12);open.setTextColor(GREEN);open.setMinHeight(dp(44));open.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24087f8c),null,shape(Color.WHITE,12)));open.setOnClickListener(v->selectTab(1));label.addView(open);root.addView(label);space(root,6);
        LinearLayout poster=card();poster.setPadding(dp(8),dp(8),dp(8),dp(12));
        ImageView thumbnail=new ImageView(this){@Override protected void onMeasure(int w,int h){int width=View.MeasureSpec.getSize(w);super.onMeasure(w,View.MeasureSpec.makeMeasureSpec(Math.min((int)(width*1664f/1500),dp(280)),View.MeasureSpec.EXACTLY));}};thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);thumbnail.setBackground(shape(AppUi.SOFT,12));thumbnail.setContentDescription("实际成绩 B30 缩略图，点击进入B30页面");thumbnail.setFocusable(true);thumbnail.setClickable(true);thumbnail.setOnClickListener(v->selectTab(1));poster.addView(thumbnail,new LinearLayout.LayoutParams(-1,-2));TextView caption=text("正在生成 O20 + N10 成绩图…",12,MUTED,false);caption.setPadding(dp(8),dp(8),dp(8),0);poster.addView(caption);
        space(root,6);root.addView(text("最近游玩 · 50条",18,INK,true));space(root,10);renderRecent();
        final int generation=renderGeneration;final Engine snapshot=engine;
        previews.execute(()->{try{PosterData.Board board=PosterData.load(this,snapshot,false,PosterPanel.rules(this),0);Bitmap full=new ScorePoster(this).render(board);Bitmap small=Bitmap.createScaledBitmap(full,750,832,true);full.recycle();final double total=board.rating();
            runOnUiThread(()->{if(generation!=renderGeneration||isDestroyed()){small.recycle();return;}homeImage=small;thumbnail.setImageBitmap(small);rating.setText(String.format(Locale.ROOT,"%.4f",total));name.setText(board.player);title.setText(board.title);breakdown.setText("O20 "+board.old.size()+"/20  ·  N10 "+board.fresh.size()+"/10");caption.setText("");caption.setVisibility(View.GONE);});
        }catch(Exception e){runOnUiThread(()->{if(generation==renderGeneration)caption.setText("成绩图暂未生成，请进入 B30 页面重试。");});}});
    }
    private TextView metric(LinearLayout row,String value,String label){LinearLayout cell=new LinearLayout(this);cell.setOrientation(1);TextView number=text(value,20,INK,true);number.setMaxLines(1);if(Build.VERSION.SDK_INT>=26)number.setAutoSizeTextTypeUniformWithConfiguration(12,20,1,android.util.TypedValue.COMPLEX_UNIT_SP);cell.addView(number);cell.addView(text(label,12,MUTED,false));row.addView(cell,new LinearLayout.LayoutParams(0,-2,1));return number;}
    private void renderRecent(){
        List<RecentPlays.Play> recent=RecentPlays.load(engine.scores,engine.charts());
        if(recent.isEmpty()){LinearLayout empty=card();empty.addView(text("还没有收录成绩",18,INK,true));space(empty,8);empty.addView(text("完成游玩后同步本机成绩，或在设置中恢复 QQ 云存档。",14,MUTED,false));return;}
        root.addView(text("最近 "+recent.size()+" 次游玩 · 按时间倒序 · 保留同曲多次记录",13,MUTED,false));space(root,12);
        SongCatalogue catalogue=new SongCatalogue(engine.charts());
        LinearLayout cards=new LinearLayout(this);cards.setOrientation(LinearLayout.VERTICAL);root.addView(cards);
        final int[] columns={0};
        cards.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            int count=r-l>=dp(560)?2:1;if(columns[0]==count)return;columns[0]=count;cards.removeAllViews();
            for(int i=0;i<recent.size();i+=count){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.TOP);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.bottomMargin=dp(12);cards.addView(row,rp);
                for(int j=0;j<count;j++){LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,-2,1);if(j>0)cp.leftMargin=dp(12);
                    if(i+j<recent.size()){RecentPlays.Play play=recent.get(i+j);row.addView(new RecentPlayCard(this,play,i+j+1,catalogue.forChart(play.chart.optInt("cid"))),cp);}else row.addView(new View(this),cp);
                }
            }
        });
    }
    private void renderSettings(){
        heading("设置","读取、云存档与谱库管理");
        pairingEntry();
        status=text(lastMessage.isEmpty()?"成绩保存在本机，可随时同步云存档。":lastMessage,13,GREEN,false);root.addView(status);space(root,12);
        LinearLayout grid=new LinearLayout(this);grid.setGravity(Gravity.TOP);root.addView(grid,new LinearLayout.LayoutParams(-1,-2));LinearLayout[] columns={AppUi.column(this,0),AppUi.column(this,0)};
        for(int i=0;i<2;i++){LinearLayout.LayoutParams column=new LinearLayout.LayoutParams(0,-2,1);if(i==1)column.leftMargin=dp(12);grid.addView(columns[i],column);}
        LinearLayout appearance=settingsCard(columns[0]);appearance.addView(text("玩家名片",18,INK,true));appearance.addView(text("本地头像与昵称；歌名称号在 B30 页选择",14,MUTED,false));button(appearance,"设置头像与昵称",false,()->startActivityForResult(new Intent(this,PlayerProfileActivity.class),20));
        LinearLayout cloud=settingsCard(columns[1]);cloud.addView(text("QQ 云存档",18,INK,true));String qq=ScoreStore.profile(this);
        cloud.addView(text(qq.isEmpty()?"先设置QQ号，保存和恢复你的成绩。":"当前存档 · QQ "+qq,15,MUTED,false));
        cloudStatus=text(getSharedPreferences("cloud",0).getString("last_result","尚未同步云端，本机成绩保留。"),13,AppUi.ACCENT,false);space(cloud,8);cloud.addView(cloudStatus);
        button(cloud,qq.isEmpty()?"设置 QQ 并同步":"切换 QQ 存档",false,this::bindQq);
        if(!qq.isEmpty()){
            Switch automatic=new Switch(this);automatic.setText("本机读取后自动合并到云端");automatic.setTextSize(14);automatic.setTextColor(INK);automatic.setMinHeight(dp(48));automatic.setChecked(getSharedPreferences("cloud",0).getBoolean("auto",true));automatic.setOnCheckedChangeListener((v,checked)->getSharedPreferences("cloud",0).edit().putBoolean("auto",checked).apply());cloud.addView(automatic);
            button(cloud,"合并同步云端（上传＋拉取）",true,()->startCloud(true));button(cloud,"只从云端拉取",false,()->startCloud(false));
        }
        cloud.addView(text("QQ号仅作存档编号，暂不验证归属。不同QQ的本地存档分别保存。",12,MUTED,false));
        LinearLayout reader=settingsCard(columns[0]);reader.addView(text("读取与筛选",18,INK,true));space(reader,4);reader.addView(text("首次依次开启开发者模式、无线调试，再按引导配对；之后点一次同步即可。默认账号自动更新谱库，无需手填账号。",14,MUTED,false));
        button(reader,"同步本机成绩",true,()->startActivityForResult(new Intent(this,InternalReaderActivity.class).putExtra("sync_now",true),12));

        LinearLayout server=settingsCard(columns[1]);server.addView(text("我的谱面服务器",18,INK,true));server.addView(text(engine.server,13,MUTED,false));
        long updated=engine.catalog.optLong("updated_at");server.addView(text("清单更新："+date(updated),12,MUTED,false));
        button(server,"更新谱库（默认账号）",false,()->runTask(()->DefaultCatalog.refresh(this,engine,true,this::message)));button(server,"导出匹配成绩 JSON",false,()->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"malody-link-scores.json");startActivityForResult(i,11);});

        LinearLayout imports=settingsCard(columns[1]);imports.addView(text("群文件批量导入",18,INK,true));imports.addView(text("先在 QQ 批量下载，再在 App 中多选谱面包。",14,MUTED,false));button(imports,"QQ群文件下载路径",false,()->startActivity(new Intent(this,QqDownloadSettingsActivity.class)));button(imports,"选择和确认谱面包",true,()->startActivity(new Intent(this,ChartImportActivity.class)));
        LinearLayout about=settingsCard(columns[0]);about.addView(text("关于 Malody Link",18,INK,true));space(about,8);about.addView(text(appVersion()+" · O20 + N10\n仅收录当前服务器谱库成绩；预览参数不改写原始记录。",13,MUTED,false));button(about,"获取更新",false,()->startActivity(new Intent(this,UpdateActivity.class)));
        space(root,8);root.addView(text("QQ机器人尚未接入；成绩数值字段仍待核对。",12,MUTED,false));
    }
    private String appVersion(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception unavailable){return "未知版本";}}
    private int validAliases(){int count=0;Iterator<String> it=engine.aliases.keys();while(it.hasNext()){JSONObject a=engine.aliases.optJSONObject(it.next());if(a!=null&&engine.active(a.optInt("cid"),a.optString("server_hash")))count++;}return count;}
    private String date(long seconds){if(seconds<=0)return "未知";SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA);f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));return f.format(new Date(seconds*1000));}
    private void message(String s){runOnUiThread(()->{lastMessage=s;if(status!=null)status.setText(s);});}
    private interface Task {String run()throws Exception;}
    public void uploadScoresFromB30(){if(ScoreStore.profile(this).isEmpty())bindQq();else startCloud(true);}
    private void startCloud(boolean upload){try{CloudSyncService.request(this,upload);message(upload?"正在合并云端成绩…":"正在从云端恢复成绩…");}catch(Exception e){message(e.getMessage());}}
    private void bindQq(){
        LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(dp(24),dp(8),dp(24),0);
        EditText qq=input(form,"QQ号",ScoreStore.profile(this),false);qq.setInputType(InputType.TYPE_CLASS_NUMBER);qq.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(12)});
        form.addView(text("首次设置会迁移当前本机成绩；切换QQ会切换本地成绩列表，原存档保留。QQ号仅作存档编号，不验证QQ归属。",13,MUTED,false));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("设置 QQ 云存档").setView(form).setNegativeButton("取消",null).setPositiveButton("保存并同步",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(busy||ScoreSyncGate.busy()||LocalSyncService.active||CloudSyncService.active){qq.setError("同步正在进行，请结束后再切换存档");return;}
            String value;try{value=CloudRecords.qq(qq.getText().toString());}catch(IllegalArgumentException e){qq.setError(e.getMessage());return;}
            dialog.dismiss();runTask(()->{ScoreStore.bind(this,value,engine.charts());engine=new Engine(this);CloudSyncService.request(this,true);return "已选择 QQ "+value+"，正在同步云存档。";});
        }));dialog.show();
    }
    private String saveAndCloud(Task task)throws Exception {
        if(!ScoreSyncGate.enter("import"))throw new IllegalStateException("其他同步正在进行，请稍后重试");
        try{String result=task.run();if(CloudScoreSync.automatic(this))try{result+="\n"+CloudScoreSync.merge(this,engine,true,this::message);}catch(Exception e){result+="\n本机成绩已保存；云端未完成，可稍后重试。";}return result;}
        finally{ScoreSyncGate.leave("import");}
    }
    private void runTask(Task task){if(busy)return;busy=true;message("正在处理，请稍候…");worker.execute(()->{String result;try{result=task.run();}catch(Exception e){result="未完成："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}String done=result;runOnUiThread(()->{busy=false;if(!isFinishing()&&!isDestroyed()){render();message(done);}});});}
    private void authorize(){
        if(!Shizuku.pingBinder()){new AlertDialog.Builder(this).setTitle("需要启动 Shizuku").setMessage("普通 App 无法直接读取 Malody 数据目录。请先安装并启动 Shizuku，再回来授权；也可以继续使用 USB 同步。").setPositiveButton("打开 Shizuku",(d,w)->{Intent i=getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");if(i!=null)startActivity(i);else startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://shizuku.rikka.app/download/")));}).setNegativeButton("稍后",null).show();return;}
        try{if(Shizuku.checkSelfPermission()==PackageManager.PERMISSION_GRANTED)bindReader();else Shizuku.requestPermission(100);}catch(Exception e){message("授权失败："+e.getMessage());}
    }
    private void bindReader(){try{serviceArgs=new Shizuku.UserServiceArgs(new ComponentName(this,ReadOnlyService.class)).daemon(false).processNameSuffix("malody_readonly").version(2);Shizuku.bindUserService(serviceArgs,connection);message("正在连接只读服务…");}catch(Exception e){message("连接失败："+e.getMessage());}}
    private void scan(){if(readerBinder==null||!readerBinder.isBinderAlive()){authorize();return;}Engine.Reader reader=Engine.binderReader(readerBinder);runTask(()->saveAndCloud(()->engine.scan(reader,this::message)));}
    private EditText input(LinearLayout l,String label,String value,boolean password){l.addView(text(label,14,INK,false));EditText e=new EditText(this);e.setSingleLine(true);e.setTextColor(INK);e.setTextSize(16);e.setText(value);e.setInputType(password?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT);l.addView(e,new LinearLayout.LayoutParams(-1,dp(54)));return e;}
    private void login(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);l.setPadding(dp(24),dp(12),dp(24),0);EditText address=input(l,"服务器地址",engine.server,false),user=input(l,"账号用户名","",false),pass=input(l,"密码（不保存）","",true);
        l.addView(text("只读取谱面清单和待核验谱面，不向 OpenList 上传成绩。",12,MUTED,false));new AlertDialog.Builder(this).setTitle("连接谱库").setView(l).setNegativeButton("取消",null).setPositiveButton("连接",(d,w)->{String a=address.getText().toString(),u=user.getText().toString(),p=pass.getText().toString();pass.setText("");runTask(()->{engine.connect(a,u,p,this::message);return "谱库已更新："+engine.charts().length()+" 张有效谱面。可开始读取成绩。";});}).show();}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==20){if(result==RESULT_OK)render();return;}if(request==12){if(result==RESULT_OK)runTask(()->{engine=new Engine(this);return "已更新本机收录成绩。";});return;}if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if(request==10)runTask(()->saveAndCloud(()->engine.importReplay(Engine.read(getContentResolver().openInputStream(uri),33554432))?"已收录 1 条匹配成绩":"已跳过：谱面未经核验、不属于当前谱库，或成绩已存在。"));
        if(request==11)runTask(()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){out.write(engine.export().toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}return "已导出当前谱库匹配成绩。";});
    }
}
