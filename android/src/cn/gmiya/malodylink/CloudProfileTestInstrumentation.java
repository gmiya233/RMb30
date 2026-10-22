package cn.gmiya.malodylink;

import android.app.Instrumentation;
import android.content.*;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.util.UUID;

/** Test state is isolated under cache; no real QQ preferences or player score files are changed. */
public final class CloudProfileTestInstrumentation extends Instrumentation {
    private String qq;
    @Override public void onCreate(Bundle b){super.onCreate(b);qq=b==null?null:b.getString("test_qq");start();}
    private void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private Context sandbox(String id){Context base=getTargetContext();File root=new File(base.getCacheDir(),"cloud-test-"+id);root.mkdirs();return new ContextWrapper(base){
        @Override public File getFilesDir(){File f=new File(root,"files");f.mkdirs();return f;}
        @Override public File getCacheDir(){File f=new File(root,"cache");f.mkdirs();return f;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences("cloud-test-"+id+"-"+name,mode);}
    };}
    @Override public void onStart(){Bundle out=new Bundle();int code=-1;
        try{
            qq=CloudRecords.qq(qq);Context first=sandbox(UUID.randomUUID().toString());Engine engine=new Engine(first);JSONObject c=engine.charts().getJSONObject(0);int cid=c.getInt("cid");String md5="0123456789abcdef0123456789abcdef";
            JSONObject row=new JSONObject().put("id",cid+":1700000001:"+md5).put("cid",cid).put("server_hash",c.getString("server_hash")).put("chart_md5",md5).put("played_at",1700000001L).put("score_candidate",100).put("accuracy_candidate",99.5).put("player","SYNTHETIC CLOUD TEST").put("raw_score_fields",new JSONObject().put("4",3).put("5",100));
            ScoreStore.write(ScoreStore.file(first,"","scores.json"),new JSONArray().put(row));ScoreStore.bind(first,qq,engine.charts());
            check(new Engine(first).scores.length()==1,"first binding migrates legacy scores");
            String second=qq.substring(0,qq.length()-1)+(qq.endsWith("1")?"2":"1");ScoreStore.bind(first,second,engine.charts());check(new Engine(first).scores.length()==0,"switching QQ starts separate local archive");
            ScoreStore.bind(first,qq,engine.charts());check(new Engine(first).scores.length()==1&&ScoreStore.scores(first,"").length()==1,"switching back restores QQ archive and keeps legacy original");
            String uploaded=CloudScoreSync.run(first,true,p->{});check(!ScoreSyncGate.busy(),"cloud sync releases shared gate");
            Context fresh=sandbox(UUID.randomUUID().toString());Engine empty=new Engine(fresh);ScoreStore.bind(fresh,qq,empty.charts());String downloaded=CloudScoreSync.run(fresh,false,p->{});
            Engine restored=new Engine(fresh);check(restored.scores.length()==1&&restored.scores.getJSONObject(0).getLong("score_candidate")==100,"empty phone restores raw score from real server");
            String repeated=CloudScoreSync.run(fresh,true,p->{});check(repeated.contains("上传 0 条"),"repeat does not upload duplicates");
            check(!ScoreStore.file(first,qq,"replay-index.json").equals(ScoreStore.file(first,second,"replay-index.json")),"replay cache isolated per QQ");
            out.putString("stream","PASS: isolated QQ migration, profile switching, legacy preservation, real Android Rank1 upload, fresh-phone cloud restore and idempotent merge.\n");
        }catch(Throwable e){code=0;StringWriter trace=new StringWriter();e.printStackTrace(new PrintWriter(trace));out.putString("stream","FAIL: "+trace+"\n");}
        finish(code,out);
    }
}
