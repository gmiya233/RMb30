package cn.gmiya.malodylink;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

/** Standalone entry remains available; the main B30 tab uses the same panel. */
public final class PosterActivity extends Activity {
    private PosterPanel panel;
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(0xff0f172a);getWindow().setNavigationBarColor(0xff0f172a);panel=new PosterPanel(this,true);View content=panel.view();setContentView(content);content.setOnApplyWindowInsetsListener((v,in)->{int side=(int)(16*getResources().getDisplayMetrics().density+.5f);v.setPadding(side,in.getSystemWindowInsetTop(),side,in.getSystemWindowInsetBottom());return in;});}
    @Override protected void onDestroy(){if(panel!=null)panel.close();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int req,String[] p,int[] r){super.onRequestPermissionsResult(req,p,r);if(panel!=null)panel.onRequestPermissionsResult(req,p,r);}
}
