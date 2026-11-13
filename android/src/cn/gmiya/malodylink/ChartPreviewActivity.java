package cn.gmiya.malodylink;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

/** Displays only server-generated images; importing or playing a chart is not required. */
public final class ChartPreviewActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private PosterPreviewView preview;private TextView status;private Button previous,next,save,refresh;
    private JSONObject chart;private String server;private ChartPreviewData document;private Bitmap bitmap;
    private int page,generation;private boolean loading;
    public static void open(Activity host,int cid){host.startActivity(new Intent(host,ChartPreviewActivity.class).putExtra("cid",cid));}
    @Override public void onCreate(Bundle state){super.onCreate(state);page=state==null?0:state.getInt("page");
        LinearLayout body=AppUi.column(this,16);body.setBackgroundColor(AppUi.BG);getWindow().setStatusBarColor(AppUi.BG);getWindow().setNavigationBarColor(AppUi.BG);
        body.addView(AppUi.button(this,"返回",this::finish));body.addView(AppUi.text(this,"谱面预览",24,AppUi.INK,true));
        status=AppUi.text(this,"正在读取谱面…",14,AppUi.MUTED,false);body.addView(status);
        preview=new PosterPreviewView(this);preview.setMaximumZoom(16);preview.setContentDescription("直轨谱面展开图，双指缩放，双击放大，拖动查看");body.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout pages=new LinearLayout(this);previous=AppUi.button(this,"上一页",()->{if(page>0){page--;load(false);}});next=AppUi.button(this,"下一页",()->{page++;load(false);});pages.addView(previous,new LinearLayout.LayoutParams(0,-2,1));pages.addView(next,new LinearLayout.LayoutParams(0,-2,1));body.addView(pages);
        LinearLayout actions=new LinearLayout(this);refresh=AppUi.button(this,"刷新",()->load(true));save=AppUi.button(this,"保存",this::save);Button zoom=AppUi.button(this,"放大 / 适应",()->preview.toggleZoom());actions.addView(refresh,new LinearLayout.LayoutParams(0,-2,1));actions.addView(save,new LinearLayout.LayoutParams(0,-2,1));actions.addView(zoom,new LinearLayout.LayoutParams(0,-2,1));body.addView(actions);
        setContentView(body);body.setOnApplyWindowInsetsListener((v,in)->{int p=AppUi.dp(this,16);v.setPadding(p,in.getSystemWindowInsetTop(),p,in.getSystemWindowInsetBottom());return in;});
        try{Engine engine=new Engine(this);server=engine.server;for(int i=0;i<engine.charts().length();i++){JSONObject row=engine.charts().getJSONObject(i);if(row.optInt("cid")==getIntent().getIntExtra("cid",0)){chart=row;break;}}if(chart==null)throw new IOException("谱库中未找到该谱面");load(true);}catch(Exception e){status.setText(e.getMessage());enabled();}
    }
    private void enabled(){previous.setEnabled(!loading&&document!=null&&page>0);next.setEnabled(!loading&&document!=null&&page+1<document.pages.size());save.setEnabled(!loading&&bitmap!=null);refresh.setEnabled(!loading&&chart!=null);}
    private void load(boolean manifest){if(loading||chart==null)return;loading=true;preview.setBitmap(null);if(bitmap!=null){bitmap.recycle();bitmap=null;}enabled();status.setText("正在加载直轨预览…");final int request=++generation;
        io.execute(()->{try{
            if(!server.equals(RankConnection.config(this).getString("server")))throw new IOException("预览服务器不匹配");OpenListScoreRemote source=RankConnection.open(this);
            File cache=new File(getCacheDir(),"chart-previews/"+CloudRecords.sha256(server.getBytes(StandardCharsets.UTF_8)));cache.mkdirs();
            if(manifest||document==null){File cached=new File(cache,chart.optInt("cid")+".json");byte[] bytes;try{bytes=source.getPath("/_chart_previews/v1/"+chart.optInt("cid")+".json",16384);}catch(Exception offline){if(!cached.isFile())throw offline;bytes=Engine.read(new FileInputStream(cached),16384);}
                ChartPreviewData verified=new ChartPreviewData(new JSONObject(new String(bytes,StandardCharsets.UTF_8)),server,chart);try(FileOutputStream out=new FileOutputStream(cached)){out.write(bytes);}document=verified;page=Math.min(Math.max(0,page),document.pages.size()-1);}
            ChartPreviewData.Page item=document.pages.get(page);File image=new File(cache,item.digest+".png");byte[] bytes=null;if(image.isFile())try{byte[] old=Engine.read(new FileInputStream(image),4*1024*1024);ChartPreviewData.verify(item,old);bytes=old;}catch(Exception corrupt){}
            if(bytes==null){bytes=source.getPath("/_chart_previews/v1/"+item.image,4*1024*1024);ChartPreviewData.verify(item,bytes);try(FileOutputStream out=new FileOutputStream(image)){out.write(bytes);}}
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);if(bounds.outWidth!=item.width||bounds.outHeight!=item.height)throw new IOException("预览图尺寸不匹配");
            Bitmap result=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(result==null)throw new IOException("预览图无法解码");
            runOnUiThread(()->{if(isDestroyed()||request!=generation){result.recycle();return;}Bitmap old=bitmap;bitmap=result;preview.setBitmap(result);if(old!=null)old.recycle();loading=false;status.setText(chart.optString("title")+" · "+chart.optString("version")+"\nEX Rhythm Master VI · "+document.keys+"K · "+document.notes+"音符 · "+(page+1)+"/"+document.pages.size()+"页");enabled();});
        }catch(Exception e){runOnUiThread(()->{if(isDestroyed()||request!=generation)return;loading=false;status.setText(e.getMessage()==null?"谱面预览暂不可用，请稍后重试":e.getMessage());enabled();});}});
    }
    private void save(){if(bitmap==null||loading)return;if(android.os.Build.VERSION.SDK_INT<29&&checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},83);return;}loading=true;enabled();io.execute(()->{String message;try{GallerySaver.save(this,bitmap,"谱面预览-"+chart.optInt("cid")+"-"+(page+1));message="预览已保存到相册";}catch(Exception e){message="保存失败，请检查相册权限后重试";}String result=message;runOnUiThread(()->{if(isDestroyed())return;loading=false;status.setText(result);enabled();});});}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==83&&results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)save();}
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("page",page);super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){generation++;io.shutdownNow();preview.setBitmap(null);if(bitmap!=null&&!loading)bitmap.recycle();super.onDestroy();}
}
