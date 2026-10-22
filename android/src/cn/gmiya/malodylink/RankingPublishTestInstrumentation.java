package cn.gmiya.malodylink;

import android.app.Instrumentation;
import android.content.*;
import org.json.*;
import android.os.Bundle;
import java.io.*;
import java.util.*;

/** Republishes only derived indices for the current already-uploaded real archive. */
public final class RankingPublishTestInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    @Override public void onStart(){Bundle result=new Bundle();int code=-1;try{
        Context base=getTargetContext();OpenListScoreRemote source=RankConnection.open(base);List<String> players=source.players();if(players.isEmpty())throw new AssertionError("requires existing cloud player");String qq=players.get(0);final File isolated=new File(base.getCacheDir(),"index-publisher-test-"+java.util.UUID.randomUUID());isolated.mkdirs();final String prefs="index-publisher-test";base.getSharedPreferences(prefs,0).edit().putString("qq",qq).commit();Context testing=new ContextWrapper(base){public File getFilesDir(){return isolated;}public File getCacheDir(){return isolated;}public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(name.equals("cloud")?prefs:name,mode);}};Engine engine=new Engine(testing);engine.scores=CloudArchive.sync(source,qq,engine.server,new JSONArray(),engine.charts(),false,p->{}).merged.scores;List<String> originals=source.list(engine.profile,false);if(originals.isEmpty())throw new AssertionError("requires existing cloud archive");
        File scores=ScoreStore.file(base,ScoreStore.profile(base),"scores.json");byte[] before=ScoreStore.exists(scores)?ScoreStore.read(scores):null;
        RankingPublisher.publish(testing,source,engine,p->{});RankingPublisher.publish(testing,source,engine,p->{});base.deleteSharedPreferences(prefs);
        if(!originals.equals(source.list(engine.profile,false)))throw new AssertionError("history file list changed");if(!Arrays.equals(before,ScoreStore.exists(scores)?ScoreStore.read(scores):null))throw new AssertionError("local raw score changed");
        RankingRepository.Snapshot fresh=RankingRepository.refresh(testing,engine,true,p->{});RankingData.Player expected=RankingData.player(engine.profile,engine.scores,engine.charts());RankingData.Player found=null;for(RankingData.Player p:fresh.players)if(p.qq.equals(engine.profile))found=p;if(found==null||Math.abs(found.rating-expected.rating)>1e-9)throw new AssertionError("published rating differs from raw archive");
        result.putString("stream","PASS: Android index publisher, repeated publication, compact index retrieval, expected RT, unchanged local and cloud history.\n");
    }catch(Throwable e){code=0;StringWriter trace=new StringWriter();e.printStackTrace(new PrintWriter(trace));result.putString("stream","FAIL: "+trace+"\n");}finish(code,result);}
}
