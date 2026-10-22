package cn.gmiya.malodylink;

import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.util.*;

/** Synthetic SC poster integration; isolated preferences and files, no cloud operations. */
public final class RatingPosterTestInstrumentation extends Instrumentation {
    private void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    @Override public void onStart(){Bundle result=new Bundle();int code=-1;try{
        Context base=getTargetContext();String id="rating-test-"+UUID.randomUUID();File folder=new File(base.getCacheDir(),id);folder.mkdirs();Context test=new ContextWrapper(base){
            @Override public File getFilesDir(){return folder;}
            @Override public SharedPreferences getSharedPreferences(String n,int mode){return base.getSharedPreferences(id+"-"+n,mode);}
        };
        Engine real=new Engine(base);File originalFile=ScoreStore.file(base,real.profile,"scores.json");byte[] original=ScoreStore.exists(originalFile)?ScoreStore.read(originalFile):null;
        Engine engine=new Engine(test);JSONArray charts=new JSONArray(),scores=new JSONArray();
        for(int i=0;i<2;i++){int cid=900001+i;charts.put(new JSONObject().put("cid",cid).put("sid",cid).put("title",i==0?"SC9 Fixture":"SC14 Fixture").put("version",i==0?"SC9":"SC14 LV.14").put("level",i==0?32:14).put("server_hash","fixture").put("promote",i));
            scores.put(new JSONObject().put("cid",cid).put("server_hash","fixture").put("player","SYNTHETIC TEST").put("score_candidate",1000000).put("accuracy_candidate",99.5).put("raw_score_fields",new JSONObject().put("4",3)));}
        engine.catalog=new JSONObject().put("charts",charts);engine.scores=scores;
        PosterData.Board b=PosterData.load(test,engine,false,new RatingRules(),0);check(b.old.size()==1&&b.fresh.size()==1,"SC fixtures grouped");
        check(b.old.get(0).displayLevel()==13&&b.old.get(0).converted==13,"SC9 overrides raw32");check(b.fresh.get(0).displayLevel()==15&&b.fresh.get(0).converted==15,"SC14 overrides catalogue14");
        check(Math.abs(b.oldRating()-.65)<1e-9&&Math.abs(b.newRating()-1.5)<1e-9&&Math.abs(b.rating()-28.0/30)<1e-9,"section and overall rating");
        check(LocalPlayerProfile.DEFAULT_TITLE.equals(b.title),"default title shown without unlocked rating or selected song");
        test.getSharedPreferences("poster",0).edit().putString("entry_900001",new JSONObject().put("level",15).put("accuracy",100).put("judge",5).toString()).commit();
        check(Math.abs(LocalPlayerProfile.actualRating(test,engine)-28.0/30)<1e-9&&LocalPlayerProfile.DEFAULT_TITLE.equals(LocalPlayerProfile.title(test,engine)),"preview changes cannot unlock rating titles");
        test.getSharedPreferences("poster",0).edit().remove("entry_900001").commit();
        PosterData.Board judge=PosterData.load(test,engine,false,new RatingRules(),1);check(judge.old.get(0).displayLevel()==13&&Math.abs(judge.old.get(0).converted-13*.78)<1e-9,"judge-only preview keeps SC conversion");
        Bitmap bitmap=new ScorePoster(test).render(b);check(bitmap.getWidth()==1500&&bitmap.getHeight()==1664,"poster dimensions");
        try(FileOutputStream out=new FileOutputStream(new File(base.getCacheDir(),"rating-sc-fixture.png"))){check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out),"fixture PNG rendered");}bitmap.recycle();
        check(Arrays.equals(original,ScoreStore.exists(originalFile)?ScoreStore.read(originalFile):null),"original player scores preserved");
        result.putString("stream","PASS: isolated SC9/SC14 loading, level precedence, judge preview, O20/N10/overall rating, native PNG and unchanged player scores.\n");
    }catch(Throwable e){code=0;StringWriter trace=new StringWriter();e.printStackTrace(new PrintWriter(trace));result.putString("stream","FAIL: "+trace+"\n");}finish(code,result);}
}
