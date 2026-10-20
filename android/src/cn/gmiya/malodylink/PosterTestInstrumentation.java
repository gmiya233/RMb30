package cn.gmiya.malodylink;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.util.*;

public final class PosterTestInstrumentation extends Instrumentation {
    private boolean cleanupFixture;
    private void check(boolean ok,String s)throws Exception {if(!ok)throw new Exception(s);}
    @Override public void onCreate(Bundle a){super.onCreate(a);cleanupFixture=a!=null&&"true".equals(a.getString("cleanup_fixture"));start();}
    @Override public void onStart(){Bundle result=new Bundle();int resultCode=-1;Context context=getTargetContext();String key="entry_600065";SharedPreferences prefs=context.getSharedPreferences("poster",0);String previous=prefs.getString(key,null);
        try{
            File scores=new File(context.getFilesDir(),"scores.json");byte[] before=Engine.read(new FileInputStream(scores),16000000);Engine engine=new Engine(context);
            PosterData.Board demo=PosterData.load(context,engine,true,new RatingRules(),0);check(demo.old.size()==20&&demo.fresh.size()==10,"demo O20/N10 capacities");check(demo.demo,"demo explicitly marked");
            for(PosterData.Entry e:demo.fresh){boolean promoted=false;for(int i=0;i<engine.charts().length();i++){JSONObject c=engine.charts().getJSONObject(i);if(c.optInt("cid")==e.cid){promoted=c.optInt("promote")==1||c.optString("path").startsWith("promote/");break;}}check(promoted,"N entry belongs to promote");}
            Bitmap image=new ScorePoster(context).render(demo);check(image.getWidth()==1500&&image.getHeight()==1664,"PNG dimensions");try(FileOutputStream out=new FileOutputStream(new File(context.getCacheDir(),"score-poster-demo.png"))){check(image.compress(Bitmap.CompressFormat.PNG,100,out),"PNG encode");}
            final Throwable[] previewFailure={null};runOnMainSync(()->{try{
                PosterPreviewView view=new PosterPreviewView(context);view.setBitmap(image);
                int[][] sizes={{984,1000},{280,1800},{1200,300}};
                for(int[] size:sizes){view.layout(0,0,size[0],size[1]);RectF r=view.imageBounds();check(r.left>=-.01&&r.top>=-.01&&r.right<=size[0]+.01&&r.bottom<=size[1]+.01,"fit preview fully inside viewport");check(Math.abs(r.width()/r.height()-1500f/1664)<.001,"preview preserves aspect ratio");view.toggleZoom();view.toggleZoom();check(view.imageBounds().equals(r),"zoom resets to full image");}
            }catch(Throwable e){previewFailure[0]=e;}});if(previewFailure[0]!=null)throw new Exception("preview",previewFailure[0]);
            android.net.Uri saved=GallerySaver.save(context,image,true);
            try{
                try(InputStream in=context.getContentResolver().openInputStream(saved)){Bitmap decoded=BitmapFactory.decodeStream(in);check(decoded!=null&&decoded.getWidth()==1500&&decoded.getHeight()==1664,"gallery saved a complete PNG");if(decoded!=null)decoded.recycle();}
                try(android.database.Cursor cursor=context.getContentResolver().query(saved,new String[]{"relative_path","is_pending","mime_type"},null,null,null)){check(cursor!=null&&cursor.moveToFirst()&&cursor.getString(0).equals("Pictures/Malody Link/")&&cursor.getInt(1)==0&&cursor.getString(2).equals("image/png"),"gallery image published in album");}
            }finally{context.getContentResolver().delete(saved,null,null);}image.recycle();
            check(RatingRules.normalizedLevel(23)==11&&RatingRules.normalizedLevel(29)==13&&RatingRules.normalizedLevel(32)==14&&RatingRules.normalizedLevel(35)==15,"MaV to RM mapping on device");
            check(Math.abs(new RatingRules().converted(24,99.8,3)-12.12)<1e-8,"level normalization before rating");
            prefs.edit().putString(key,new JSONObject().put("level",12).put("accuracy",99.8).put("judge",3).toString()).commit();
            PosterData.Board real=PosterData.load(context,engine,false,new RatingRules(),0);check(real.old.size()==1&&real.fresh.isEmpty(),"real record stays in daily table");check(Math.abs(real.old.get(0).converted-12.12)<1e-8&&real.old.get(0).manual,"preview edit uses custom formula");
            check(Arrays.equals(before,Engine.read(new FileInputStream(scores),16000000)),"preview edits do not modify raw score file");
            result.putString("stream","PASS: full-image preview (portrait/narrow/landscape), zoom reset, gallery PNG save/readback, level normalization, poster native render, O20/N10 membership, 1500x1664 PNG, custom formula and raw-score preservation.\n");
        }catch(Throwable e){result.putString("stream","FAIL: "+e.toString()+"\n");resultCode=0;}
        finally{SharedPreferences.Editor edit=prefs.edit();boolean fixture=false;try{if(previous!=null){JSONObject value=new JSONObject(previous);fixture=cleanupFixture&&value.optDouble("level")==12&&value.optDouble("accuracy")==99.8&&value.optInt("judge")==3;}}catch(Exception ignored){}
            if(previous==null||fixture)edit.remove(key);else edit.putString(key,previous);edit.commit();}
        // finish() can terminate the process immediately; restore settings first.
        finish(resultCode,result);
    }
}
