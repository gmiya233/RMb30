package cn.gmiya.malodylink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

/** First Android release of chart attributes; no player rating is modified. */
public final class ChartRadarActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private static final int BG=0xff080c18,INK=0xfff6f7fc,MUTED=0xffa9b5ca;
    private RadarPreviewView preview;private TextView status;private ImageButton save;private ChartRadarData radar;private boolean saving;
    private ImageButton icon(String label,int kind,boolean primary,Runnable action){
        ImageButton button=new ImageButton(this);button.setImageDrawable(new NavIcon(kind,primary?0xffffb5cd:INK));
        int pad=AppUi.dp(this,13);button.setPadding(pad,pad,pad,pad);button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setContentDescription(label);button.setTooltipText(label);
        GradientDrawable surface=new GradientDrawable();surface.setColor(primary?0xff352033:0xff151c2d);surface.setCornerRadius(AppUi.dp(this,13));surface.setStroke(AppUi.dp(this,1),primary?0xff85465e:0xff343c54);
        button.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ff709d),surface,null));button.setOnClickListener(v->action.run());return button;
    }
    private void information(){new AlertDialog.Builder(this).setTitle("谱面六维说明").setMessage("HELL 表示该维度超过当前等级参考框。\n\n外框随等级调整，数字保留原值；可双指缩放、拖动查看。\n\nIMD 谱面的变速暂按0显示。当前样本更新于 "+(radar==null?"2026-10-30":radar.updated)+"。尚未分析或已更新内容的谱面会提示等待更新。").setPositiveButton("知道了",null).show();}
    public static void open(Activity host,int cid){host.startActivity(new Intent(host,ChartRadarActivity.class).putExtra("cid",cid));}
    @Override public void onCreate(Bundle state){super.onCreate(state);
        LinearLayout body=AppUi.column(this,0);body.setBackgroundColor(BG);getWindow().setBackgroundDrawable(new ColorDrawable(BG));getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()&~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR));
        if(android.os.Build.VERSION.SDK_INT>=29)getWindow().setNavigationBarContrastEnforced(false);
        LinearLayout heading=new LinearLayout(this);heading.setPadding(AppUi.dp(this,12),AppUi.dp(this,4),AppUi.dp(this,12),AppUi.dp(this,10));
        heading.addView(icon("返回",12,false,this::finish),new LinearLayout.LayoutParams(AppUi.dp(this,48),AppUi.dp(this,48)));
        TextView title=AppUi.text(this,"谱面六维",20,INK,false);title.setTypeface(RadarFonts.get(this).medium);title.setPadding(AppUi.dp(this,14),0,0,0);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(icon("六维说明",13,false,this::information),new LinearLayout.LayoutParams(AppUi.dp(this,48),AppUi.dp(this,48)));heading.setGravity(Gravity.CENTER_VERTICAL);body.addView(heading);
        status=AppUi.text(this,"正在绘制谱面六维…",14,MUTED,false);status.setPadding(AppUi.dp(this,16),0,AppUi.dp(this,16),AppUi.dp(this,8));status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(status);
        preview=new RadarPreviewView(this);preview.setBackgroundColor(BG);preview.setContentDescription("谱面六维图，可双指缩放、拖动查看");body.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_HORIZONTAL);actions.setPadding(AppUi.dp(this,12),AppUi.dp(this,8),AppUi.dp(this,12),AppUi.dp(this,12));
        save=icon("保存图片到相册",3,true,this::save);save.setEnabled(false);ImageButton[] buttons={save,icon("重播展开动画",4,false,()->preview.replay()),icon("放大或适应屏幕",8,false,()->preview.toggleZoom())};
        for(int i=0;i<buttons.length;i++){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(AppUi.dp(this,56),AppUi.dp(this,56));if(i>0)params.leftMargin=AppUi.dp(this,20);actions.addView(buttons[i],params);}body.addView(actions);
        setContentView(body);body.setOnApplyWindowInsetsListener((v,in)->{v.setPadding(0,in.getSystemWindowInsetTop(),0,in.getSystemWindowInsetBottom());return in;});
        final int cid=getIntent().getIntExtra("cid",0);
        io.execute(()->{try{
            Engine engine=new Engine(this);JSONObject chart=null;
            for(int i=0;i<engine.charts().length();i++)if(engine.charts().getJSONObject(i).optInt("cid")==cid){chart=engine.charts().getJSONObject(i);break;}
            if(chart==null)throw new java.io.IOException("当前谱库中未找到该谱面");
            JSONObject doc=new JSONObject(new String(Engine.read(getAssets().open("chart-radar.json"),4000000),StandardCharsets.UTF_8));
            ChartRadarData data=ChartRadarData.find(doc,engine.server,chart);
            runOnUiThread(()->{if(isDestroyed())return;radar=data;preview.setData(data);status.setVisibility(View.GONE);save.setEnabled(true);});
        }catch(Exception error){runOnUiThread(()->{if(!isDestroyed())status.setText(error.getMessage()==null?"六维图暂不可用":error.getMessage());});}});
    }
    private void save(){
        if(radar==null||saving)return;
        if(android.os.Build.VERSION.SDK_INT<29&&checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},87);return;}
        saving=true;save.setEnabled(false);ChartRadarData selected=radar;
        io.execute(()->{String message;Bitmap target=null;try{target=new RadarPoster(this).render(selected);GallerySaver.save(this,target,"谱面六维-"+selected.cid);message="六维图已保存到相册";}catch(Exception error){message="保存失败，请检查相册权限";}finally{if(target!=null)target.recycle();}String value=message;runOnUiThread(()->{saving=false;if(!isDestroyed()){save.setEnabled(true);Toast.makeText(this,value,Toast.LENGTH_LONG).show();}});});
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);if(code==87&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)save();}
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
}
