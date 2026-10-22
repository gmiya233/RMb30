package cn.gmiya.malodylink;

import android.accessibilityservice.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.accessibility.*;
import java.util.*;

/** Opt-in, three-minute local pairing session. No screenshots, files, gestures or network. */
public final class PairCodeAccessibilityService extends AccessibilityService {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable capture=this::captureDialog;
    public static boolean enabled(Context c){AccessibilityManager m=(AccessibilityManager)c.getSystemService(ACCESSIBILITY_SERVICE);if(m==null)return false;for(AccessibilityServiceInfo info:m.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)){android.content.pm.ServiceInfo s=info.getResolveInfo().serviceInfo;if(c.getPackageName().equals(s.packageName)&&PairCodeAccessibilityService.class.getName().equals(s.name))return true;}return false;}
    public static boolean automatic(Context c){return c.getSharedPreferences("reader",0).getBoolean("auto_pair_code",false)&&enabled(c);}
    public static void settings(Context c){c.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){if(!PairingService.canReadAutomatic()||!automatic(this))return;CharSequence pkg=event.getPackageName();if(pkg==null||!PairCodeParser.settingsPackage(pkg.toString()))return;handler.removeCallbacks(capture);handler.postDelayed(capture,120);}
    private void captureDialog(){
        if(!PairingService.canReadAutomatic()||!automatic(this))return;AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return;
        CharSequence pkg=root.getPackageName();if(pkg==null||!PairCodeParser.settingsPackage(pkg.toString())){root.recycle();return;}
        List<String> values=new ArrayList<>();boolean[] field={false};int[] remaining={512};try{collect(root,values,field,remaining,0);String code=PairCodeParser.extract(values,field[0]);if(code!=null)PairingService.submitAutomatic(this,code);}finally{root.recycle();values.clear();}
    }
    private void collect(AccessibilityNodeInfo node,List<String> values,boolean[] field,int[] remaining,int depth){
        if(depth>24||remaining[0]--<=0||node.isPassword()||!node.isVisibleToUser())return;
        String id=node.getViewIdResourceName();if(id!=null&&(id.endsWith(":id/pairing_code")||id.endsWith(":id/adb_pairing_code")||id.endsWith(":id/pairing_code_value")))field[0]=true;
        CharSequence text=node.getText();if(text!=null&&text.length()<=512)values.add(text.toString());
        for(int i=0;i<node.getChildCount()&&remaining[0]>0;i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)try{collect(child,values,field,remaining,depth+1);}finally{child.recycle();}}
    }
    @Override public void onInterrupt(){handler.removeCallbacksAndMessages(null);}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
