package cn.gmiya.malodylink;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.net.Uri;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;

public final class PosterPanel extends ContextWrapper {
    private final Activity host;private final boolean standalone;private Button uploadButton;private TextView uploadStatus;private boolean receiverRegistered;
    private final BroadcastReceiver uploads=new BroadcastReceiver(){public void onReceive(Context c,Intent i){updateUploadState();}};private boolean closed;private LinearLayout view;private View titleBar;
    public PosterPanel(Activity host,boolean standalone){super(host);this.host=host;this.standalone=standalone;demo=standalone&&host.getIntent().getBooleanExtra("demo",false);try{engine=new Engine(this);loadRules();build();IntentFilter filter=new IntentFilter(LocalScoreSync.UPDATED);if(Build.VERSION.SDK_INT>=33)registerReceiver(uploads,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(uploads,filter);receiverRegistered=true;redraw();}catch(Exception e){view=new LinearLayout(this);view.addView(text("无法生成成绩图："+e.getMessage(),14,MUTED));}}
    public View view(){return view;}
    private void runOnUiThread(Runnable task){host.runOnUiThread(task);}
    private boolean isFinishing(){return closed||host.isFinishing();}
    private boolean isDestroyed(){return host.isDestroyed();}
    public void close(){closed=true;if(receiverRegistered){unregisterReceiver(uploads);receiverRegistered=false;}if(preview!=null)preview.setBitmap(null);image=null;}
    private Engine engine;private Bitmap image;private PosterPreviewView preview;private TextView hint;
    private boolean six;private boolean demo;private int judgeOverride=0;private RatingRules rules;
    private PosterData.Board board;private boolean rendering=false,saving=false;
    private final int BG=AppUi.BG,INK=AppUi.INK,MUTED=AppUi.MUTED;
    private int dp(float x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(6),0,dp(6));return t;}
    private void button(LinearLayout parent,String label,Runnable task){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(INK);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(AppUi.SOFT));b.setMinHeight(dp(48));parent.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->{if(!rendering&&!saving)task.run();});}
    public static RatingRules rules(Context context){android.content.SharedPreferences p=context.getSharedPreferences("poster",0);double[] values=RatingRules.PRESET.clone();for(int i=1;i<=5;i++)try{values[i]=Double.parseDouble(p.getString("multiplier_"+i,Double.toString(values[i])));}catch(Exception ignored){}return new RatingRules(values);}
    private void loadRules(){rules=rules(this);}
    private void build(){
        view=new LinearLayout(this){@Override protected void onMeasure(int w,int h){boolean compact=View.MeasureSpec.getMode(h)!=View.MeasureSpec.UNSPECIFIED&&View.MeasureSpec.getSize(h)<dp(360);if(titleBar!=null)titleBar.setVisibility(compact&&!standalone?View.GONE:View.VISIBLE);super.onMeasure(w,h);}};view.setOrientation(1);view.setBackground(new CharacterBackdrop(this,1));view.setPadding(dp(16),dp(8),dp(16),dp(8));
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);
        if(standalone){Button back=new Button(this);back.setText("返回");back.setAllCaps(false);back.setTextColor(INK);back.setMinHeight(dp(48));back.setOnClickListener(v->host.finish());bar.addView(back);}
        TextView title=text("我的 B30",24,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        titleBar=bar;view.addView(bar);
        uploadButton=new Button(this);uploadButton.setText("上传成绩到服务器和排行榜");uploadButton.setTextSize(16);uploadButton.setAllCaps(false);uploadButton.setMinHeight(dp(52));uploadButton.setTextColor(AppUi.ON_SUCCESS);uploadButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(AppUi.SUCCESS));uploadButton.setOnClickListener(v->uploadScores());view.addView(uploadButton,new LinearLayout.LayoutParams(-1,-2));
        uploadStatus=text("",12,MUTED);uploadStatus.setMaxLines(2);uploadStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);view.addView(uploadStatus);updateUploadState();
        CheckBox sixToggle=new CheckBox(this);sixToggle.setText("6K B30（仅6K谱面）");sixToggle.setTextColor(INK);sixToggle.setChecked(six);view.addView(sixToggle);sixToggle.setOnCheckedChangeListener((b,on)->{if(rendering||saving){sixToggle.setChecked(six);return;}six=on;redraw();});
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);actions.setPadding(0,dp(4),0,dp(8));
        iconButton(actions,"保存到相册",3,this::save);iconButton(actions,"选择称号",6,this::chooseTitle);iconButton(actions,"调整与示例",7,this::options);iconButton(actions,"放大 / 适应屏幕",8,()->preview.toggleZoom());view.addView(actions);
        hint=text("正在生成成绩图…",12,MUTED);hint.setMaxLines(3);hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);view.addView(hint);
        preview=new PosterPreviewView(this);preview.setContentDescription("O20日常歌曲与N10新歌成绩分表，双指缩放，放大后拖动查看");view.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void uploadScores(){
        if(host instanceof MainActivity){((MainActivity)host).uploadScoresFromB30();updateUploadState();return;}
        if(ScoreStore.profile(this).isEmpty()){new AlertDialog.Builder(this).setMessage("首次上传需要设置 QQ 存档编号。").setPositiveButton("前往设置",(d,w)->host.startActivity(new Intent(this,MainActivity.class).putExtra("tab",4))).setNegativeButton("取消",null).show();return;}
        try{CloudSyncService.request(this,true);updateUploadState();}catch(Exception e){uploadStatus.setText(e.getMessage());}
    }
    private void updateUploadState(){if(closed||uploadButton==null)return;
        boolean syncing=CloudSyncService.active||LocalSyncService.active||ScoreSyncGate.busy();uploadButton.setEnabled(!syncing);
        uploadButton.setText(CloudSyncService.active?"正在上传并更新排行榜…":"上传成绩到服务器和排行榜");
        if(CloudSyncService.active)uploadStatus.setText(LocalScoreSync.currentProgress.isEmpty()?"正在连接云存档…":LocalScoreSync.currentProgress);
        else if(syncing)uploadStatus.setText("其他同步正在进行，完成后可上传。");
        else uploadStatus.setText(ScoreStore.profile(this).isEmpty()?"首次上传先设置 QQ；仅上传原始成绩，不包含手动预览参数。":getSharedPreferences("cloud",0).getString("last_result","仅上传原始成绩，不包含手动预览参数。"));
    }
    private void feedback(String message){hint.setText(message);hint.setVisibility(message==null||message.isEmpty()?View.GONE:View.VISIBLE);}
    private void iconButton(LinearLayout parent,String label,int kind,Runnable task){ImageButton b=new ImageButton(this);b.setImageDrawable(new NavIcon(kind,INK));b.setScaleType(ImageView.ScaleType.FIT_CENTER);b.setPadding(dp(14),dp(14),dp(14),dp(14));b.setContentDescription(label);b.setTooltipText(label);android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();background.setColor(AppUi.SOFT);background.setCornerRadius(dp(12));b.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x24087f8c),background,null));LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(dp(56),dp(56));size.rightMargin=dp(8);parent.addView(b,size);b.setOnClickListener(v->{if(!rendering&&!saving)task.run();});}
    private void chooseTitle(){try{Engine current=new Engine(this);List<Map.Entry<String,String>> entries=new ArrayList<>(LocalPlayerProfile.titleChoices(this,current).entrySet());String selected=LocalPlayerProfile.selectedTitleKey(this,current);String[] labels=new String[entries.size()+1];labels[0]="自动称号（"+LocalPlayerProfile.ratingTitle(LocalPlayerProfile.actualRating(this,current))+"）";int checked=0;for(int i=0;i<entries.size();i++){Map.Entry<String,String> e=entries.get(i);labels[i+1]=e.getKey().startsWith("rank:")?e.getValue()+(e.getKey().equals("rank:0")?" · 默认":" · rating ≥"+e.getKey().substring(5)):"歌名 · "+e.getValue();if(e.getKey().equals(selected))checked=i+1;}new AlertDialog.Builder(this).setTitle("选择已解锁称号").setSingleChoiceItems(labels,checked,(dialog,index)->{try{Engine latest=new Engine(this);if(!engine.profile.equals(latest.profile))throw new IOException("QQ存档已切换，请重新进入 B30");LocalPlayerProfile.saveTitle(this,latest,index==0?"":entries.get(index-1).getKey());engine=latest;dialog.dismiss();redraw();}catch(Exception e){feedback(e.getMessage());}}).setNegativeButton("取消",null).show();}catch(Exception e){feedback("无法读取称号："+e.getMessage());}}
    private void options(){
        new AlertDialog.Builder(this).setTitle("成绩图选项").setItems(new String[]{"rating 规则 / 倍率","调整单曲预览参数","全图预览判定",demo?"返回实际成绩":"查看示例布局","放大 / 适应屏幕","选择称号","头像与昵称（仅本地）"},(d,index)->{
            if(index==0)configureRules();else if(index==1)chooseEntry();else if(index==2)new AlertDialog.Builder(this).setTitle("全图预览判定").setSingleChoiceItems(new String[]{"原始判定（待核对）","预览 A判","预览 B判","预览 C判","预览 D判","预览 E判"},judgeOverride,(dialog,which)->{judgeOverride=which;dialog.dismiss();redraw();}).setNegativeButton("取消",null).show();else if(index==3){demo=!demo;redraw();}else if(index==4)preview.toggleZoom();else if(index==5)chooseTitle();else host.startActivityForResult(new Intent(this,PlayerProfileActivity.class),20);
        }).setNegativeButton("取消",null).show();
    }
    private void redraw(){if(rendering||saving||engine==null)return;rendering=true;feedback("正在排版…");
        new Thread(()->{try{PosterData.Board next=PosterData.loadKeys(this,engine,demo,rules,judgeOverride,six?6:0);Bitmap bitmap=new ScorePoster(this).render(next);
            // Cache is only a preview/export artifact; raw imported scores are untouched.
            try(FileOutputStream out=new FileOutputStream(new File(getCacheDir(),six?"score-poster-6k.png":demo?"score-poster-demo.png":"score-poster-real.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
            runOnUiThread(()->{rendering=false;if(isFinishing()||isDestroyed()){bitmap.recycle();return;}Bitmap previous=image;image=bitmap;board=next;preview.setBitmap(image);if(previous!=null)previous.recycle();
                feedback("");});
        }catch(Exception e){runOnUiThread(()->{rendering=false;feedback("排版失败："+e.getMessage());});}}).start();
    }
    private void save(){
        if(image==null||saving)return;
        if(Build.VERSION.SDK_INT<29&&checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED){host.requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},51);return;}
        final Bitmap snapshot=image.copy(Bitmap.Config.ARGB_8888,false);final boolean sample=demo;saving=true;feedback("正在保存到相册…");
        new Thread(()->{try{GallerySaver.save(this,snapshot,sample);runOnUiThread(()->{saving=false;feedback("");Toast.makeText(this,"已保存到相册 Malody Link",Toast.LENGTH_LONG).show();});}
            catch(Exception e){runOnUiThread(()->{saving=false;feedback("保存失败："+e.getMessage());});}finally{snapshot.recycle();}}).start();
    }
    public void onRequestPermissionsResult(int req,String[] permissions,int[] results){if(req==51){if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)save();else feedback("需要存储权限才能保存到相册");}}
    private EditText field(LinearLayout form,String label,String value){form.addView(text(label,14,INK));EditText e=new EditText(this);e.setText(value);e.setTextColor(INK);e.setTextSize(16);e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);form.addView(e,new LinearLayout.LayoutParams(-1,dp(52)));return e;}
    private void configureRules(){LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(dp(22),dp(8),dp(22),0);form.addView(text("rating = 折算等级 × 准度系数 × 判定倍率\nSC1～4→12；SC5～13→13；SC14+→15\n其他原始等级 >16 按 MaV→RM 表映射，16→8\n17～18→9；19～20→10；21～23→11\n24～26→12；27～29→13；30～32→14；33～35→15\n低于99.5%：(准度/99.5)²\n99.5%～99.8%：1+(准度−99.5)/30\n99.8%以上：1.01",14,MUTED));EditText[] inputs=new EditText[5];for(int i=0;i<5;i++)inputs[i]=field(form,RatingRules.judgeName(i+1)+"倍率",Double.toString(rules.multipliers[i+1]));form.addView(text("预设参考历史社区倍率，未声明为当前版本官方 MM。更改判定不会自动重算一次历史游玩的准度。",12,MUTED));ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("rating 规则与判定倍率").setView(scroll).setNegativeButton("取消",null).setPositiveButton("应用",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{double[] vals=rules.multipliers.clone();for(int i=1;i<=5;i++)vals[i]=Double.parseDouble(inputs[i-1].getText().toString());RatingRules next=new RatingRules(vals);android.content.SharedPreferences.Editor edit=getSharedPreferences("poster",0).edit();for(int i=1;i<=5;i++)edit.putString("multiplier_"+i,Double.toString(vals[i]));edit.apply();rules=next;dialog.dismiss();redraw();}catch(Exception e){inputs[0].setError("请输入大于0且不超过10的倍率");}}));dialog.show();}
    private void chooseEntry(){if(demo){new AlertDialog.Builder(this).setMessage("示例模式不修改实际成绩。请切回实际记录后调整单曲预览参数。").setPositiveButton("知道了",null).show();return;}if(board==null||board.count()==0){feedback("当前还没有可调整的实际成绩。");return;}List<PosterData.Entry> entries=new ArrayList<>(board.old);entries.addAll(board.fresh);String[] titles=new String[entries.size()];for(int i=0;i<titles.length;i++)titles[i]=entries.get(i).title+" / "+entries.get(i).version;new AlertDialog.Builder(this).setTitle("选择要调整的单曲").setItems(titles,(d,w)->editEntry(entries.get(w))).show();}
    private void editEntry(PosterData.Entry entry){LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(dp(22),dp(8),dp(22),0);EditText level=field(form,"原始等级（>16 按MaV→RM表）",Double.isFinite(entry.level)?Double.toString(entry.level):""),acc=field(form,"准度（%）",Double.toString(entry.accuracy));form.addView(text("判定",14,INK));Spinner judge=new Spinner(this);ArrayAdapter<String> ad=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,new String[]{"A判","B判","C判","D判","E判"});ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);judge.setAdapter(ad);judge.setSelection(Math.max(0,entry.judge-1));form.addView(judge,new LinearLayout.LayoutParams(-1,dp(52)));form.addView(text("仅调整本图预览，不改变原始成绩；卡片将加 * 标记。",12,MUTED));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(entry.title).setView(form).setNegativeButton("取消",null).setNeutralButton("恢复原始",(d,w)->{getSharedPreferences("poster",0).edit().remove("entry_"+entry.cid).apply();redraw();}).setPositiveButton("应用",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{double l=Double.parseDouble(level.getText().toString()),a=Double.parseDouble(acc.getText().toString());if(l<=0||l>100)throw new Exception();RatingRules.accuracyFactor(a);JSONObject o=new JSONObject().put("level",l).put("accuracy",a).put("judge",judge.getSelectedItemPosition()+1);getSharedPreferences("poster",0).edit().putString("entry_"+entry.cid,o.toString()).apply();dialog.dismiss();redraw();}catch(Exception e){acc.setError("等级需在0～100之间，准度需在0～100%之间");}}));dialog.show();}
}
