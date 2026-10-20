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

public final class PosterActivity extends Activity {
    private Engine engine;private Bitmap image;private PosterPreviewView preview;private TextView hint;
    private boolean demo;private int judgeOverride=0;private RatingRules rules;
    private PosterData.Board board;private boolean rendering=false,saving=false;
    private final int BG=0xff0f172a,INK=0xfff1f5f9,MUTED=0xffbacada;
    private int dp(float x){return (int)(x*getResources().getDisplayMetrics().density+.5f);}
    private TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(6),0,dp(6));return t;}
    private void button(LinearLayout parent,String label,Runnable task){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(INK);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff25465c));b.setMinHeight(dp(48));parent.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->{if(!rendering&&!saving)task.run();});}
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        demo=getIntent().getBooleanExtra("demo",false);try{engine=new Engine(this);loadRules();build();redraw();}catch(Exception e){new AlertDialog.Builder(this).setMessage("无法生成成绩图："+e.getMessage()).setPositiveButton("返回",(d,w)->finish()).show();}}
    private void loadRules(){android.content.SharedPreferences p=getSharedPreferences("poster",0);double[] values=RatingRules.PRESET.clone();for(int i=1;i<=5;i++)try{values[i]=Double.parseDouble(p.getString("multiplier_"+i,Double.toString(values[i])));}catch(Exception ignored){}rules=new RatingRules(values);}
    private void build(){
        LinearLayout screen=new LinearLayout(this);screen.setOrientation(1);screen.setBackgroundColor(BG);screen.setPadding(dp(16),0,dp(16),0);setContentView(screen);
        screen.setOnApplyWindowInsetsListener((v,in)->{screen.setPadding(dp(16),in.getSystemWindowInsetTop()+dp(10),dp(16),in.getSystemWindowInsetBottom());return in;});
        LinearLayout bar=new LinearLayout(this);bar.setOrientation(0);TextView back=text("‹ 返回",16,0xff80e6ed);back.setPadding(dp(2),dp(8),dp(16),dp(8));back.setMinHeight(dp(48));back.setOnClickListener(v->finish());bar.addView(back);TextView title=text("O20 + N10 成绩图",18,INK);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));screen.addView(bar);
        LinearLayout controls=new LinearLayout(this);controls.setOrientation(0);
        LinearLayout left=new LinearLayout(this);left.setOrientation(1);LinearLayout right=new LinearLayout(this);right.setOrientation(1);controls.addView(left,new LinearLayout.LayoutParams(0,-2,1));controls.addView(right,new LinearLayout.LayoutParams(0,-2,1));
        button(left,"保存到相册",this::save);button(right,"换算规则 / 倍率",this::configureRules);screen.addView(controls);
        LinearLayout selector=new LinearLayout(this);selector.setGravity(Gravity.CENTER_VERTICAL);
        Switch sample=new Switch(this);sample.setText("示例布局");sample.setTextColor(INK);sample.setChecked(demo);sample.setMinHeight(dp(48));sample.setOnCheckedChangeListener((b,on)->{if(rendering)return;demo=on;redraw();});selector.addView(sample,new LinearLayout.LayoutParams(0,-2,1));
        Spinner judges=new Spinner(this);String[] labels={"原始判定（待核对）","预览 A判","预览 B判","预览 C判","预览 D判","预览 E判"};ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);judges.setAdapter(adapter);judges.setMinimumHeight(dp(48));judges.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> p){}public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){if(judgeOverride!=pos){judgeOverride=pos;redraw();}}});selector.addView(judges,new LinearLayout.LayoutParams(0,-2,1.4f));screen.addView(selector);
        hint=text("生成中…",12,MUTED);screen.addView(hint);
        preview=new PosterPreviewView(this);preview.setContentDescription("O20日常歌曲与N10新歌成绩分表，双指缩放，放大后拖动查看");screen.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout bottom=new LinearLayout(this);bottom.setOrientation(0);LinearLayout b1=new LinearLayout(this);b1.setOrientation(1);LinearLayout b2=new LinearLayout(this);b2.setOrientation(1);bottom.addView(b1,new LinearLayout.LayoutParams(0,-2,1));bottom.addView(b2,new LinearLayout.LayoutParams(0,-2,1));button(b1,"放大 / 适应屏幕",()->preview.toggleZoom());button(b2,"调整单曲预览参数",this::chooseEntry);screen.addView(bottom);
    }
    private void redraw(){if(rendering||saving||engine==null)return;rendering=true;hint.setText("正在排版…");
        new Thread(()->{try{PosterData.Board next=PosterData.load(this,engine,demo,rules,judgeOverride);Bitmap bitmap=new ScorePoster(this).render(next);
            // Cache is only a preview/export artifact; raw imported scores are untouched.
            try(FileOutputStream out=new FileOutputStream(new File(getCacheDir(),demo?"score-poster-demo.png":"score-poster-real.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
            runOnUiThread(()->{rendering=false;if(isFinishing()||isDestroyed()){bitmap.recycle();return;}Bitmap previous=image;image=bitmap;board=next;preview.setBitmap(image);if(previous!=null)previous.recycle();
                hint.setText((demo?"示例数据 · ":"实际记录 · ")+"O "+next.old.size()+"/20  N "+next.fresh.size()+"/10 · 准度/判定调整只影响本图试算");});
        }catch(Exception e){runOnUiThread(()->{rendering=false;hint.setText("排版失败："+e.getMessage());});}}).start();
    }
    private void save(){
        if(image==null||saving)return;
        if(Build.VERSION.SDK_INT<29&&checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},51);return;}
        final Bitmap snapshot=image.copy(Bitmap.Config.ARGB_8888,false);final boolean sample=demo;saving=true;hint.setText("正在保存到相册…");
        new Thread(()->{try{GallerySaver.save(this,snapshot,sample);runOnUiThread(()->{saving=false;hint.setText("已保存到相册 · Pictures/Malody Link · 1500 × 1664");Toast.makeText(this,"已保存到相册 Malody Link",Toast.LENGTH_LONG).show();});}
            catch(Exception e){runOnUiThread(()->{saving=false;hint.setText("保存失败："+e.getMessage());});}finally{snapshot.recycle();}}).start();
    }
    @Override public void onRequestPermissionsResult(int req,String[] permissions,int[] results){super.onRequestPermissionsResult(req,permissions,results);if(req==51){if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)save();else hint.setText("需要存储权限才能保存到相册");}}
    private EditText field(LinearLayout form,String label,String value){form.addView(text(label,14,INK));EditText e=new EditText(this);e.setText(value);e.setTextColor(INK);e.setTextSize(16);e.setSingleLine(true);e.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);form.addView(e,new LinearLayout.LayoutParams(-1,dp(52)));return e;}
    private void configureRules(){LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(dp(22),dp(8),dp(22),0);form.addView(text("自定义换算 = 折算等级 × 准度系数 × 判定倍率\n原始等级 >16 按 MaV→RM 表映射，16→8\n17～18→9；19～20→10；21～23→11\n24～26→12；27～29→13；30～32→14；33～35→15\n低于99.5%：(准度/99.5)²\n99.5%～99.8%：1+(准度−99.5)/30\n99.8%以上：1.01",14,MUTED));EditText[] inputs=new EditText[5];for(int i=0;i<5;i++)inputs[i]=field(form,RatingRules.judgeName(i+1)+"倍率",Double.toString(rules.multipliers[i+1]));form.addView(text("预设参考历史社区倍率，未声明为当前版本官方 MM。更改判定不会自动重算一次历史游玩的准度。",12,MUTED));ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("换算规则与判定倍率").setView(scroll).setNegativeButton("取消",null).setPositiveButton("应用",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{double[] vals=rules.multipliers.clone();for(int i=1;i<=5;i++)vals[i]=Double.parseDouble(inputs[i-1].getText().toString());RatingRules next=new RatingRules(vals);android.content.SharedPreferences.Editor edit=getSharedPreferences("poster",0).edit();for(int i=1;i<=5;i++)edit.putString("multiplier_"+i,Double.toString(vals[i]));edit.apply();rules=next;dialog.dismiss();redraw();}catch(Exception e){inputs[0].setError("请输入大于0且不超过10的倍率");}}));dialog.show();}
    private void chooseEntry(){if(demo){new AlertDialog.Builder(this).setMessage("示例模式不修改实际成绩。请切回实际记录后调整单曲预览参数。").setPositiveButton("知道了",null).show();return;}if(board==null||board.count()==0){hint.setText("当前还没有可调整的实际成绩。");return;}List<PosterData.Entry> entries=new ArrayList<>(board.old);entries.addAll(board.fresh);String[] titles=new String[entries.size()];for(int i=0;i<titles.length;i++)titles[i]=entries.get(i).title+" / "+entries.get(i).version;new AlertDialog.Builder(this).setTitle("选择要调整的单曲").setItems(titles,(d,w)->editEntry(entries.get(w))).show();}
    private void editEntry(PosterData.Entry entry){LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(dp(22),dp(8),dp(22),0);EditText level=field(form,"原始等级（>16 按MaV→RM表）",Double.isFinite(entry.level)?Double.toString(entry.level):""),acc=field(form,"准度（%）",Double.toString(entry.accuracy));form.addView(text("判定",14,INK));Spinner judge=new Spinner(this);ArrayAdapter<String> ad=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,new String[]{"A判","B判","C判","D判","E判"});ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);judge.setAdapter(ad);judge.setSelection(Math.max(0,entry.judge-1));form.addView(judge,new LinearLayout.LayoutParams(-1,dp(52)));form.addView(text("仅调整本图预览，不改变原始成绩；卡片将加 * 标记。",12,MUTED));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(entry.title).setView(form).setNegativeButton("取消",null).setNeutralButton("恢复原始",(d,w)->{getSharedPreferences("poster",0).edit().remove("entry_"+entry.cid).apply();redraw();}).setPositiveButton("应用",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{double l=Double.parseDouble(level.getText().toString()),a=Double.parseDouble(acc.getText().toString());if(l<=0||l>100)throw new Exception();RatingRules.accuracyFactor(a);JSONObject o=new JSONObject().put("level",l).put("accuracy",a).put("judge",judge.getSelectedItemPosition()+1);getSharedPreferences("poster",0).edit().putString("entry_"+entry.cid,o.toString()).apply();dialog.dismiss();redraw();}catch(Exception e){acc.setError("等级需在0～100之间，准度需在0～100%之间");}}));dialog.show();}
}
