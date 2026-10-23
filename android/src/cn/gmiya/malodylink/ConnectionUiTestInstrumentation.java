package cn.gmiya.malodylink;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;

/** Validate setup presentation without clearing pairing or changing system settings. */
public final class ConnectionUiTestInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    private void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private Object field(Object o,String name)throws Exception {Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private Button find(View view,String text){if(view instanceof Button&&text.contentEquals(((Button)view).getText()))return (Button)view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button found=find(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
    @Override public void onStart(){Bundle output=new Bundle();int code=-1;
        try{
            ActivityMonitor launcher=addMonitor(MainActivity.class.getName(),null,false);
            try(ParcelFileDescriptor launched=getUiAutomation().executeShellCommand("am start --activity-clear-top -n cn.gmiya.malodylink/.MainActivity")){Engine.read(new java.io.FileInputStream(launched.getFileDescriptor()),64000);}
            check(waitForMonitorWithTimeout(launcher,5000)!=null,"home visible");removeMonitor(launcher);
            Activity page=startActivitySync(new Intent(getTargetContext(),InternalReaderActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            Method display=InternalReaderActivity.class.getDeclaredMethod("displayStage",int.class);display.setAccessible(true);
            Method refresh=InternalReaderActivity.class.getDeclaredMethod("refresh");refresh.setAccessible(true);
            runOnMainSync(()->{try{
                Method developer=InternalReaderActivity.class.getDeclaredMethod("renderDeveloperState",boolean.class);developer.setAccessible(true);
                developer.invoke(page,false);
                check(((TextView)field(page,"developerHint")).getText().toString().contains("首次使用必须先开启开发者模式"),"new player sees developer prerequisite");
                check(((Button)field(page,"developerButton")).getText().toString().contains("关于手机"),"locked device opens version information");
                developer.invoke(page,true);
                check(((Button)field(page,"developerButton")).getText().toString().equals("进入开发者选项"),"unlocked device skips tapping version again");
                check(((TextView)field(page,"developerHint")).getText().toString().contains("顶部的总开关"),"master switch explained before wireless debugging");
                for(int stage=0;stage<4;stage++){
                    display.invoke(page,stage);String[] names={"developerStep","wirelessStep","pairStep","readyStep"};
                    for(int i=0;i<names.length;i++)check(((View)field(page,names[i])).getVisibility()==(i==stage?View.VISIBLE:View.GONE),"one setup stage at a time");
                }
                refresh.invoke(page);View advanced=(View)field(page,"advanced");check(advanced.getVisibility()==View.GONE,"advanced controls collapsed");
                if(getTargetContext().getSharedPreferences("reader",0).getBoolean("paired",false)&&android.provider.Settings.Global.getInt(getTargetContext().getContentResolver(),"adb_wifi_enabled",0)==1)
                    check(((View)field(page,"readyStep")).isShown(),"paired device shows daily sync instead of four setup steps");
                find(page.getWindow().getDecorView(),"遇到问题 / 连接选项").performClick();
                check(advanced.getVisibility()==View.VISIBLE,"advanced settings can be opened");
                find(page.getWindow().getDecorView(),"遇到问题 / 连接选项").performClick();
            }catch(Exception e){throw new RuntimeException(e);}});
            ActivityMonitor destination=addMonitor(new IntentFilter(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),new ActivityResult(Activity.RESULT_CANCELED,null),true);
            runOnMainSync(()->{try{
                Field busy=InternalReaderActivity.class.getDeclaredField("busy");busy.setAccessible(true);busy.setBoolean(page,true);
                find(page.getWindow().getDecorView(),"遇到问题 / 连接选项").performClick();
                find(page.getWindow().getDecorView(),"进入开发者选项").performClick();busy.setBoolean(page,false);
                find(page.getWindow().getDecorView(),"遇到问题 / 连接选项").performClick();
            }catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
            check(destination.getHits()==1,"settings navigation remains available during sync");removeMonitor(destination);
            // Measure each guide at a narrow width using larger text, without changing device font or display settings.
            runOnMainSync(()->{try{
                for(int stage=0;stage<4;stage++){
                    display.invoke(page,stage);View card=(View)field(page,new String[]{"developerStep","wirelessStep","pairStep","readyStep"}[stage]);
                    int width=(int)(360*page.getResources().getDisplayMetrics().density);
                    card.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
                    check(card.getMeasuredHeight()>0,"narrow guide has usable height");
                    Button action=(Button)field(page,new String[]{"developerButton","developerButton","pairButton","syncButton"}[stage]);
                    if(stage!=1)check(action.getMeasuredHeight()>=48*page.getResources().getDisplayMetrics().density,"Android touch target");
                }
                refresh.invoke(page);
            }catch(Exception e){throw new RuntimeException(e);}});
            output.putString("stream","PASS: one stage at a time, paired daily sync, collapsed fallback controls, settings available while busy, narrow-layout guide measurement. No pairing or system setting changed.\n");
        }catch(Throwable e){code=0;output.putString("stream","FAIL: "+e+"\n");}
        finish(code,output);
    }
}
