package cn.gmiya.malodylink;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.Arrays;

/** UI regression only: never imports, uploads or changes the player's score/preferences. */
public final class NavigationTestInstrumentation extends Instrumentation {
    private void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private TextView find(View v,String label){if(v instanceof TextView&&label.contentEquals(((TextView)v).getText()))return (TextView)v;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){TextView found=find(g.getChildAt(i),label);if(found!=null)return found;}}return null;}
    private boolean described(View v,String label){if(label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return true;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(described(g.getChildAt(i),label))return true;}return false;}
    private void tab(MainActivity a,int n){a.findViewById(0x1020101+n).performClick();}
    private void layout(MainActivity a,int width,int height)throws Exception{
        View screen=(View)field(a,"screen");float d=a.getResources().getDisplayMetrics().density;int w=(int)(width*d),h=(int)(height*d);screen.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));screen.layout(0,0,w,h);
        View nav=a.findViewById(0x1020100);check(nav.getBottom()<=h&&nav.getTop()>0,"navigation has reserved space");
        for(int i=0;i<5;i++){View button=a.findViewById(0x1020101+i);check(button.getWidth()>=48*d&&button.getHeight()>=48*d,"navigation touch target");check(button.getRight()<=nav.getWidth(),"navigation fits width");}
        Object panel=field(a,"posterPanel");if(panel!=null){View preview=(View)field(panel,"preview");check(preview.getWidth()>0&&preview.getHeight()>48*d,"B30 preview has space in narrow/landscape layouts");}
    }
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    @Override public void onStart(){Bundle result=new Bundle();int code=-1;MainActivity page=null;try{
        Engine before=new Engine(getTargetContext());File scores=ScoreStore.file(getTargetContext(),before.profile,"scores.json");byte[] original=ScoreStore.exists(scores)?ScoreStore.read(scores):null;
        ActivityMonitor launcher=addMonitor(MainActivity.class.getName(),null,false);
        try(ParcelFileDescriptor launched=getUiAutomation().executeShellCommand("am start --activity-clear-top -n cn.gmiya.malodylink/.MainActivity")){Engine.read(new FileInputStream(launched.getFileDescriptor()),64000);}
        page=(MainActivity)waitForMonitorWithTimeout(launcher,5000);removeMonitor(launcher);check(page!=null,"main activity visible");final MainActivity a=page;final Throwable[] failure={null};
        runOnMainSync(()->{Configuration saved=new Configuration(a.getResources().getConfiguration());try{
            check(find(a.getWindow().getDecorView(),"我的成绩")!=null,"home overview");check(find(a.getWindow().getDecorView(),"最近成绩")!=null,"recent scores on home");check(find(a.getWindow().getDecorView(),"其他服务器 / 账号")==null,"settings are not on home");
            tab(a,1);check(described(a.getWindow().getDecorView(),"保存到相册")&&described(a.getWindow().getDecorView(),"选择称号"),"B30 save/title icon controls embedded and labeled");check(a.findViewById(0x1020102).isSelected(),"B30 selected");layout(a,375,812);layout(a,812,375);
            check(described(a.getWindow().getDecorView(),"调整与示例")&&described(a.getWindow().getDecorView(),"放大 / 适应屏幕"),"adjust and fit actions have labeled icons");
            check(find(a.getWindow().getDecorView(),"调整与示例")==null&&find(a.getWindow().getDecorView(),"放大 / 适应屏幕")==null,"large text buttons removed");
            tab(a,3);check(find(a.getWindow().getDecorView(),"歌曲信息")!=null,"song catalogue tab");layout(a,375,812);tab(a,2);check(find(a.getWindow().getDecorView(),"总战力")!=null,"ranking tab");layout(a,375,812);tab(a,4);check(find(a.getWindow().getDecorView(),"QQ 云存档")!=null&&find(a.getWindow().getDecorView(),"连接设置 / 首次配对")!=null,"cloud and connection controls in settings");layout(a,375,812);
            Configuration large=new Configuration(saved);large.fontScale=2f;a.getResources().updateConfiguration(large,a.getResources().getDisplayMetrics());
            tab(a,0);layout(a,375,812);tab(a,1);layout(a,375,812);layout(a,812,375);
            a.getResources().updateConfiguration(saved,a.getResources().getDisplayMetrics());tab(a,4);a.onBackPressed();check(a.findViewById(0x1020101).isSelected(),"back returns to home");
        }catch(Throwable e){failure[0]=e;}finally{a.getResources().updateConfiguration(saved,a.getResources().getDisplayMetrics());}});
        if(failure[0]!=null)throw new Exception("navigation/layout",failure[0]);
        // Wait for asynchronous real-record thumbnail rendering, not just a placeholder.
        long deadline=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<deadline){final boolean[] ready={false};runOnMainSync(()->{try{ready[0]=field(a,"homeImage")!=null;}catch(Exception ignored){}});if(ready[0])break;Thread.sleep(150);}
        runOnMainSync(()->{try{check(field(a,"homeImage")!=null,"home thumbnail rendered");}catch(Throwable e){failure[0]=e;}});if(failure[0]!=null)throw new Exception("thumbnail",failure[0]);
        byte[] after=ScoreStore.exists(scores)?ScoreStore.read(scores):null;check(Arrays.equals(original,after),"UI preserves raw score file");
        result.putString("stream","PASS: home overview/recent scores, embedded B30, settings regrouping, five persistent tabs, back-to-home, 375dp and landscape layout, 2x app font scale, real thumbnail and unchanged scores.\n");
    }catch(Throwable e){code=0;StringWriter trace=new StringWriter();e.printStackTrace(new PrintWriter(trace));result.putString("stream","FAIL: "+trace+"\n");}finish(code,result);}
}
