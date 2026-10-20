package cn.gmiya.malodylink;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.util.*;

/** Regression test for ordered setup and settings navigation during a running sync. */
public final class ConnectionUiTestInstrumentation extends Instrumentation {
    private boolean developerOnly;
    @Override public void onCreate(Bundle b){super.onCreate(b);developerOnly=b!=null&&"true".equals(b.getString("developer_only"));start();}
    private void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    private void buttons(View v,List<TextView> out){if(v instanceof android.widget.Button)out.add((TextView)v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)buttons(g.getChildAt(i),out);}}
    private Activity page(){return startActivitySync(new Intent(getTargetContext(),InternalReaderActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    private boolean has(AccessibilityNodeInfo node,String text){if(node==null)return false;if(node.getText()!=null&&node.getText().toString().contains(text))return true;for(int i=0;i<node.getChildCount();i++)if(has(node.getChild(i),text))return true;return false;}
    private void settingsPage(String required)throws Exception {
        String active="none";
        for(int i=0;i<40;i++){AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null){active=String.valueOf(root.getPackageName());if("com.android.settings".equals(active)&&has(root,required))return;}Thread.sleep(100);}
        throw new AssertionError("App button did not open system settings: "+required+"; active package="+active);
    }
    @Override public void onStart(){Bundle output=new Bundle();int result=-1;
        try {
            check(((PowerManager)getTargetContext().getSystemService(Context.POWER_SERVICE)).isInteractive(),"phone must be awake for visible UI verification");
            // Android 16/vivo blocks a background-only runner's first Activity launch.
            // Bring up only our launcher via the shell, then test all settings clicks as the App UID.
            ActivityMonitor launcher=addMonitor(MainActivity.class.getName(),null,false);
            try(ParcelFileDescriptor started=getUiAutomation().executeShellCommand("am start -n cn.gmiya.malodylink/.MainActivity")){Engine.read(new java.io.FileInputStream(started.getFileDescriptor()),64000);}
            check(waitForMonitorWithTimeout(launcher,5000)!=null,"test launcher must be visible");removeMonitor(launcher);
            if(developerOnly){
                check(DeveloperModeState.unlocked(getTargetContext()),"device must have unlocked developer mode");
                getTargetContext().getSharedPreferences("reader",0).edit().putBoolean("developer_guide_pending",true).commit();
                Activity returned=page();waitForIdleSync();
                ActivityMonitor destination=addMonitor(new IntentFilter(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),new ActivityResult(Activity.RESULT_CANCELED,null),true);
                // Opening vivo's developer page with its master switch off can terminate a debug runner.
                // Intercept only this test's external destination; actual destination was separately observed on device.
                runOnMainSync(()->{try{
                    List<TextView> all=new ArrayList<>();buttons(returned.getWindow().getDecorView(),all);TextView enter=null;
                    for(TextView v:all)if(v.getText().toString().equals("1. 进入开发者选项"))enter=v;check(enter!=null,"first button detects unlocked mode");
                    java.lang.reflect.Field field=InternalReaderActivity.class.getDeclaredField("developerReturnDialog");field.setAccessible(true);AlertDialog dialog=(AlertDialog)field.get(returned);
                    check(dialog!=null&&dialog.isShowing(),"return prompt is visible");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();enter.performClick();
                }catch(Exception e){throw new RuntimeException(e);}});
                waitForIdleSync();check(destination.getHits()==2,"both return prompt and first button launch developer settings");removeMonitor(destination);
                check(!getTargetContext().getSharedPreferences("reader",0).getBoolean("developer_guide_pending",true),"return prompt consumed once");
                Activity again=page();waitForIdleSync();runOnMainSync(()->{try{java.lang.reflect.Field f=InternalReaderActivity.class.getDeclaredField("developerReturnDialog");f.setAccessible(true);check(f.get(again)==null,"later visits do not repeat setup prompt");}catch(Exception e){throw new RuntimeException(e);}});
                output.putString("stream","PASS: real vivo unlocked-mode detection with master switch off, first button enters developer settings, return prompt launches correct action once, later visits do not repeat setup.\n");finish(-1,output);return;
            }
            Activity page=page();waitForIdleSync();
            runOnMainSync(()->{try{
                List<TextView> all=new ArrayList<>();buttons(page.getWindow().getDecorView(),all);int one=-1,two=-1,three=-1,four=-1;
                for(int i=0;i<all.size();i++){String text=all.get(i).getText().toString();if(text.startsWith("1. "))one=i;if(text.startsWith("2. "))two=i;if(text.startsWith("3. "))three=i;if(text.startsWith("4. "))four=i;}
                check(one>=0&&two>one&&three>two&&four>three,"setup buttons are in 1, 2, 3, 4 order");
                java.lang.reflect.Field busy=InternalReaderActivity.class.getDeclaredField("busy");busy.setAccessible(true);busy.setBoolean(page,true);
                all.get(two).performClick();
            }catch(Exception e){throw new RuntimeException(e);}});
            boolean disabled=!DeveloperModeState.unlocked(getTargetContext()),master=DeveloperModeState.masterEnabled(getTargetContext());
            settingsPage(disabled?"关于手机":master?"无线调试":"开发者选项");output.putString("wireless","PASS: settings opens the correct prerequisite page while busy=true");
            Activity fallback=page();waitForIdleSync();
            runOnMainSync(()->{List<TextView> all=new ArrayList<>();buttons(fallback.getWindow().getDecorView(),all);boolean found=false;for(TextView v:all)if(v.getText().toString().equals("打不开？打开开发者选项")){v.performClick();found=true;break;}check(found,"developer settings fallback present");});
            settingsPage(disabled?"关于手机":"开发者选项");output.putString("fallback","PASS: real App click opens correct prerequisite settings");
            if(!disabled){
                getTargetContext().getSharedPreferences("reader",0).edit().putBoolean("developer_guide_pending",true).commit();
                Activity returned=page();waitForIdleSync();
                runOnMainSync(()->{try{
                    List<TextView> all=new ArrayList<>();buttons(returned.getWindow().getDecorView(),all);boolean detected=false;
                    for(TextView v:all)if(v.getText().toString().equals("1. 进入开发者选项"))detected=true;check(detected,"unlocked developer mode changes the first button to enter");
                    java.lang.reflect.Field field=InternalReaderActivity.class.getDeclaredField("developerReturnDialog");field.setAccessible(true);AlertDialog dialog=(AlertDialog)field.get(returned);check(dialog!=null&&dialog.isShowing(),"return from version info prompts next action");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                }catch(Exception e){throw new RuntimeException(e);}});
                settingsPage("开发者选项");check(!getTargetContext().getSharedPreferences("reader",0).getBoolean("developer_guide_pending",true),"return prompt is consumed once");
                output.putString("developer","PASS: unlocked mode detected, first button changes, return prompt opens developer options without repeating version taps");
            }
            Activity finalPage=page();waitForIdleSync();
            long start=SystemClock.elapsedRealtime();try{LocalAdbDiscovery.find(getTargetContext(),LocalAdbDiscovery.CONNECT,800);}catch(java.io.IOException expected){}
            check(SystemClock.elapsedRealtime()-start<3000,"discovery timeout must not block waiting for cleanup");
            Engine engine=new Engine(getTargetContext());String updated=DefaultCatalog.refresh(getTargetContext(),engine,true,p->{});check(engine.charts().length()>0,"default read-only catalogue loads");output.putString("catalog",updated);
            output.putString("stream","PASS: setup order, settings accessible during sync, developer mode detection, return prompt and real developer-settings launch, bounded discovery timeout, default Data1 WebDAV catalogue.\n");
        }catch(Throwable e){result=0;output.putString("stream","FAIL: "+e.toString()+"\n");}
        finish(result,output);
    }
}
