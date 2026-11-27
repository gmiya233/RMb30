package cn.gmiya.malodylink;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class QqDownloadSettingsActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();private TextView current,status;private Button automatic,manual;private ProgressBar progress;private boolean busy,closed;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(AppUi.BG);getWindow().setNavigationBarColor(AppUi.BG);
        LinearLayout body=AppUi.column(this,18);body.setBackgroundColor(AppUi.BG);ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(body);setContentView(scroll);body.setOnApplyWindowInsetsListener((v,in)->{body.setPadding(AppUi.dp(this,18)+in.getSystemWindowInsetLeft(),AppUi.dp(this,12)+in.getSystemWindowInsetTop(),AppUi.dp(this,18)+in.getSystemWindowInsetRight(),AppUi.dp(this,12)+in.getSystemWindowInsetBottom());return in;});
        body.addView(AppUi.button(this,"返回设置",this::finish));AppUi.space(body,16);body.addView(AppUi.text(this,"QQ群文件下载路径",25,AppUi.INK,true));AppUi.space(body,12);
        current=AppUi.text(this,"",15,AppUi.INK,true);current.setTextIsSelectable(true);body.addView(current);
        status=AppUi.text(this,"自动检测只检查 QQ 常见下载目录；手动选择使用系统文件夹授权。",14,AppUi.MUTED,false);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(status);AppUi.space(body,18);
        automatic=AppUi.button(this,"自动检测路径",this::detect);body.addView(automatic);body.addView(AppUi.text(this,"先检查已授权目录，不要求无线调试。其他受限目录再用现有本机读取配对分别检查。",14,AppUi.MUTED,false));AppUi.space(body,16);
        manual=AppUi.button(this,"手动选择路径",this::choose);body.addView(manual);body.addView(AppUi.text(this,"选择你在 QQ 中设置的下载文件夹。Android 若不允许选 Android/data，请选择公共 Download/QQ 文件夹。",14,AppUi.MUTED,false));
        progress=new ProgressBar(this);progress.setVisibility(View.GONE);body.addView(progress);AppUi.space(body,20);
        body.addView(AppUi.button(this,"打开群文件批量导入",()->startActivity(new Intent(this,ChartImportActivity.class))));
        body.addView(AppUi.button(this,"本机读取／首次配对",()->startActivity(new Intent(this,InternalReaderActivity.class))));refresh();
    }
    private void refresh(){current.setText("当前路径\n"+QqDownloads.description(this));automatic.setEnabled(!busy);manual.setEnabled(!busy);progress.setVisibility(busy?View.VISIBLE:View.GONE);}
    private void ui(Runnable r){runOnUiThread(()->{if(!closed)r.run();});}
    private void detect(){if(busy)return;busy=true;status.setText("正在检查常见下载目录和文件访问权限…");refresh();io.execute(()->{
        try{List<QqDownloadPolicy.Directory> results=QqDownloads.detect(this);ui(()->{busy=false;refresh();if(results.isEmpty()){status.setText("未找到常见 QQ 下载目录，请手动选择 QQ 当前的保存位置。");return;}
            String[] labels=new String[results.size()];for(int i=0;i<labels.length;i++){QqDownloadPolicy.Directory d=results.get(i);labels[i]=d.path+"\n"+(d.available()?d.count+" 个 MCZ · "+(d.tree.isEmpty()?"本机读取":"已授权，可直接读取")+(d.warning.isEmpty()?"":"\n"+d.warning):d.error);}
            int readable=0;for(QqDownloadPolicy.Directory d:results)if(d.available())readable++;status.setText("可读取 "+readable+" 个目录；未检查的候选路径需另行授权。");new AlertDialog.Builder(this).setTitle("选择检测到的下载路径").setItems(labels,(dialog,index)->{try{QqDownloads.auto(this,results.get(index));status.setText("已保存自动检测路径。下次导入会读取这个目录。"+results.get(index).warning);refresh();saved();}catch(Exception invalid){status.setText(invalid.getMessage());}}).setNegativeButton("取消",null).show();
        });}catch(Exception e){ui(()->{busy=false;status.setText(e.getMessage()==null?"检测失败，可改用手动选择目录":e.getMessage());refresh();});}
    });}
    private void saved(){setResult(RESULT_OK);if(getIntent().getBooleanExtra("return_to_import",false))finish();}
    private void choose(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download/QQ"));startActivityForResult(i,81);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=81||result!=RESULT_OK||data==null)return;
        busy=true;status.setText("正在验证目录读取权限…");refresh();Uri uri=data.getData();int flags=data.getFlags();io.execute(()->{try{QqDownloads.tree(this,uri,flags);ui(()->{busy=false;status.setText("手动路径已保存，读取授权将保留供下次使用。");refresh();saved();});}catch(Exception e){ui(()->{busy=false;status.setText("目录未保存："+(e.getMessage()==null?"请重新选择文件夹":e.getMessage()));refresh();});}});
    }
    @Override protected void onDestroy(){closed=true;io.shutdownNow();super.onDestroy();}
}
