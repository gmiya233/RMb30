package cn.gmiya.malodylink;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.*;

public final class UpdateActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private TextView latest,status,notes;private Button check,action;private ProgressBar progress;
    private UpdateData release;private File ready;private int currentCode,request;private boolean closed,busy,waitingForPermission;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);getWindow().setStatusBarColor(AppUi.BG);getWindow().setNavigationBarColor(AppUi.BG);
        LinearLayout body=AppUi.column(this,16);body.setBackground(new CharacterBackdrop(this,4));setContentView(body);body.setOnApplyWindowInsetsListener((v,in)->{body.setPadding(AppUi.dp(this,16)+in.getSystemWindowInsetLeft(),AppUi.dp(this,12)+in.getSystemWindowInsetTop(),AppUi.dp(this,16)+in.getSystemWindowInsetRight(),AppUi.dp(this,12)+in.getSystemWindowInsetBottom());return in;});
        body.addView(AppUi.button(this,"返回设置",this::finish));AppUi.space(body,16);body.addView(AppUi.text(this,"获取更新",26,AppUi.INK,true));
        String version="未知";try{android.content.pm.PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),0);currentCode=info.versionCode;version=info.versionName;}catch(Exception ignored){}
        body.addView(AppUi.text(this,"当前版本 · "+version,14,AppUi.MUTED,false));AppUi.space(body,18);latest=AppUi.text(this,"正在检查服务器版本…",20,AppUi.INK,true);body.addView(latest);status=AppUi.text(this,"检查仅获取版本信息；点击下载后才获取安装包。",13,AppUi.MUTED,false);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(status);AppUi.space(body,12);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);progress.setVisibility(View.GONE);body.addView(progress,new LinearLayout.LayoutParams(-1,AppUi.dp(this,12)));
        check=AppUi.button(this,"重新检查",this::check);body.addView(check);action=AppUi.button(this,"下载更新",()->{if(ready!=null)install();else download();});action.setTextColor(AppUi.ON_SUCCESS);action.setBackgroundTintList(android.content.res.ColorStateList.valueOf(AppUi.SUCCESS));action.setEnabled(false);body.addView(action);AppUi.space(body,16);
        ScrollView scroll=new ScrollView(this);notes=AppUi.text(this,"",15,AppUi.INK,false);notes.setTextIsSelectable(true);scroll.addView(notes);body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));check();
    }
    private void deliver(Runnable action){runOnUiThread(()->{if(!closed&&!isDestroyed())action.run();});}
    private String message(Exception error){return error.getMessage()==null?"请稍后重试":error.getMessage();}
    private void busy(boolean value){busy=value;check.setEnabled(!value);action.setEnabled(!value&&release!=null&&release.code>=currentCode);}
    private void check(){
        if(busy)return;final int generation=++request;release=null;ready=null;busy(true);progress.setVisibility(View.GONE);latest.setText("正在检查服务器版本…");status.setText("正在获取最新版本信息…");
        io.execute(()->{try{UpdateData result=UpdateRepository.check(this);deliver(()->{if(generation!=request)return;release=result;busy(false);latest.setText(result.code>currentCode?"发现新版本 · "+result.name:"当前已是最新版本");status.setText("服务器版本 "+result.name+" · 安装包 "+String.format(Locale.ROOT,"%.1f MB",result.size/1048576d));notes.setText("更新内容\n\n"+result.notes);action.setText(result.code>currentCode?"下载更新":"下载安装包");});}
            catch(Exception failure){deliver(()->{if(generation!=request)return;busy(false);latest.setText("暂时无法检查更新");status.setText(message(failure));});}});
    }
    private void download(){
        if(busy||release==null)return;final UpdateData selected=release;final int generation=++request;busy(true);progress.setVisibility(View.VISIBLE);progress.setProgress(0);status.setText("正在下载安装包…");action.setText("正在下载…");
        io.execute(()->{try{File file=UpdateRepository.download(this,selected,percent->deliver(()->{if(generation==request){progress.setProgress(percent);status.setText("正在下载安装包 · "+percent+"%");}}));deliver(()->{if(generation!=request)return;ready=file;busy(false);progress.setProgress(100);action.setText("安装更新");status.setText("下载完成，文件及应用签名校验通过。点击安装更新。");});}
            catch(Exception failure){deliver(()->{if(generation!=request)return;busy(false);action.setText("重新下载");status.setText("下载未完成："+message(failure));});}});
    }
    private void install(){
        if(ready==null||busy)return;
        if(!getPackageManager().canRequestPackageInstalls()){
            waitingForPermission=true;status.setText("请允许 Malody Link 安装应用，返回后再次点击「安装更新」。");
            try{startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));}catch(Exception unavailable){waitingForPermission=false;status.setText("请在系统设置中允许此应用安装更新，再重试。");}return;
        }
        try{Uri uri=Uri.parse("content://"+getPackageName()+".updates/apk/"+ready.getName());Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("Malody Link 更新",uri));startActivity(intent);}catch(Exception failed){status.setText("无法打开系统安装界面："+message(failed));}
    }
    @Override protected void onResume(){super.onResume();if(waitingForPermission){waitingForPermission=false;if(getPackageManager().canRequestPackageInstalls())status.setText("安装权限已开启，点击「安装更新」继续。");}}
    @Override protected void onDestroy(){closed=true;request++;io.shutdownNow();super.onDestroy();}
}
