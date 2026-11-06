package cn.gmiya.malodylink;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Actual-device raster checks, isolated to this app's cache and test image. */
public final class RadarTestInstrumentation extends Instrumentation {
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    private static RadarPreviewView radarView(View root){if(root instanceof RadarPreviewView)return (RadarPreviewView)root;if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){RadarPreviewView result=radarView(group.getChildAt(i));if(result!=null)return result;}}return null;}
    private static int squareControls(View root){int count=0;if(root instanceof android.widget.ImageButton){check(root.getWidth()==root.getHeight(),"icon button remains square");check(root.getContentDescription()!=null,"icon has accessible label");count++;}if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++)count+=squareControls(group.getChildAt(i));}return count;}
    private static void frame(Context context,View view,String name)throws Exception{Bitmap image=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(image));try(FileOutputStream out=new FileOutputStream(new File(context.getCacheDir(),name))){check(image.compress(Bitmap.CompressFormat.PNG,100,out),"animation PNG");}finally{image.recycle();}}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();int code=-1;
        try{
            Context context=getTargetContext();Engine engine=new Engine(context);
            JSONObject doc=new JSONObject(new String(Engine.read(context.getAssets().open("chart-radar.json"),4000000),StandardCharsets.UTF_8));
            RadarFonts fonts=RadarFonts.get(context);Bitmap badge=Bitmap.createBitmap(180,100,Bitmap.Config.ARGB_8888);
            Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);ink.setTypeface(fonts.bold);ink.setTextSize(28);ink.setColor(Color.WHITE);
            RadarPoster.centeredText(new Canvas(badge),ink,"HELL",new RectF(20,20,160,80));
            int left=180,right=-1,top=100,bottom=-1;
            for(int y=0;y<100;y++)for(int x=0;x<180;x++)if(Color.alpha(badge.getPixel(x,y))>200){left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);}
            check(right>=left&&Math.abs((left+right+1)/2f-90)<=1.1&&Math.abs((top+bottom+1)/2f-50)<=1.1,"HELL actual glyph pixels center in both axes");badge.recycle();
            for(int cid:new int[]{600214,604332,604370}){
                JSONObject chart=null;for(int i=0;i<engine.charts().length();i++)if(engine.charts().getJSONObject(i).optInt("cid")==cid){chart=engine.charts().getJSONObject(i);break;}
                check(chart!=null,"active chart "+cid);ChartRadarData data=ChartRadarData.find(doc,engine.server,chart);
                Bitmap image=new RadarPoster(context).render(data);check(image.getWidth()==1240&&image.getHeight()==2200,"full export dimensions");
                try(FileOutputStream out=new FileOutputStream(new File(context.getCacheDir(),"radar-"+cid+".png"))){check(image.compress(Bitmap.CompressFormat.PNG,100,out),"PNG encode");}
                if(cid==600214){
                    Uri saved=GallerySaver.save(context,image,"radar-qa-test");
                    try(InputStream in=context.getContentResolver().openInputStream(saved)){Bitmap copied=BitmapFactory.decodeStream(in);check(copied!=null&&copied.getWidth()==1240&&copied.getHeight()==2200,"gallery full final frame");copied.recycle();}finally{context.getContentResolver().delete(saved,null,null);}
                    final Throwable[] error={null};runOnMainSync(()->{try{RadarPreviewView view=new RadarPreviewView(context);view.setData(data);for(int[] size:new int[][]{{280,800},{1200,300}}){view.layout(0,0,size[0],size[1]);RectF fit=view.imageBounds();check(fit.left>=-.1&&fit.top>=-.1&&fit.right<=size[0]+.1&&fit.bottom<=size[1]+.1,"fit bounds");view.toggleZoom();view.toggleZoom();check(view.imageBounds().equals(fit),"zoom reset");}}catch(Throwable failure){error[0]=failure;}});if(error[0]!=null)throw new AssertionError("view geometry",error[0]);
                }
                image.recycle();
            }
            Intent open=new Intent(context,ChartRadarActivity.class).putExtra("cid",600214).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);Activity screen=startActivitySync(open);waitForIdleSync();Thread.sleep(1200);
            final Throwable[] motionError={null};final float[] progress={-1,-1,-1};final RadarPreviewView[] view={null};
            runOnMainSync(()->{try{check(squareControls(screen.getWindow().getDecorView())==5,"five square outline controls");view[0]=radarView(screen.getWindow().getDecorView());check(view[0]!=null,"radar view exists");view[0].replay();progress[0]=view[0].animationProgress();frame(context,view[0],"radar-motion-start.png");}catch(Throwable e){motionError[0]=e;}});
            Thread.sleep(100);runOnMainSync(()->{try{progress[1]=view[0].animationProgress();frame(context,view[0],"radar-motion-middle.png");}catch(Throwable e){motionError[0]=e;}});
            Thread.sleep(800);runOnMainSync(()->{try{progress[2]=view[0].animationProgress();frame(context,view[0],"radar-motion-end.png");}catch(Throwable e){motionError[0]=e;}});
            if(motionError[0]!=null)throw new AssertionError("animation",motionError[0]);
            check(progress[2]>.999f,"animation reaches final shape");
            if(android.animation.ValueAnimator.areAnimatorsEnabled())check(progress[0]<.1&&progress[1]>progress[0]&&progress[1]<1,"center-out animation advances");
            Bitmap screenshot=getUiAutomation().takeScreenshot();if(screenshot!=null){try(FileOutputStream out=new FileOutputStream(new File(context.getCacheDir(),"radar-screen-dark.png"))){screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}screenshot.recycle();}
            result.putString("stream","PASS: B fonts, HELL pixel-centering, 3 chart posters, full gallery save/readback, zoom, dark icon UI; animation progress "+progress[0]+" / "+progress[1]+" / "+progress[2]+".\n");
        }catch(Throwable failure){code=0;result.putString("stream","FAIL: "+failure+"\n");}
        finish(code,result);
    }
}
