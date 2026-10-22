package cn.gmiya.malodylink;

import android.content.Context;
import org.json.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Called only after authoritative cloud-history upload/merge succeeded. */
public final class RankingPublisher {
    public static void publish(Context context,OpenListScoreRemote source,Engine engine,Engine.Progress progress)throws Exception {
        if(!engine.server.equals(RankConnection.config(context).getString("server")))throw new IllegalArgumentException("排行榜服务器与当前谱库不一致");
        String qq=CloudRecords.qq(engine.profile),revision=RankingIndex.revision(engine.server,engine.charts());RankingIndex.Remote remote=new OpenListRankingRemote(source);RankingData.Player player=RankingData.player(qq,engine.scores,engine.charts());long namedAt=0;for(int i=0;i<engine.scores.length();i++)namedAt=Math.max(namedAt,engine.scores.getJSONObject(i).optLong("played_at"));
        File marker=new File(context.getFilesDir(),"ranking-published/"+revision+"/"+qq+".json");JSONObject done=new JSONObject();try{if(ScoreStore.exists(marker))done=new JSONObject(new String(ScoreStore.read(marker),StandardCharsets.UTF_8));}catch(Exception corrupt){}
        List<Integer> ids=new ArrayList<>(player.best.keySet());Collections.sort(ids);int count=0;
        try{for(int cid:ids){RankingData.Score score=player.best.get(cid);JSONArray candidate=new JSONArray().put(score.record);String key=Integer.toString(cid),fingerprint=CloudRecords.sha256(ChartMatcher.canonical(new JSONObject().put("scores",candidate).put("player",player.name)).getBytes(StandardCharsets.UTF_8));if(fingerprint.equals(done.optString(key)))continue;progress.update("更新单曲排行 "+(++count)+" / "+ids.size());RankingIndex.publish(remote,engine.server,revision,cid,qq,candidate,engine.charts(),player.name,namedAt);done.put(key,fingerprint);}
            progress.update("更新 RT 排行索引…");RankingIndex.publish(remote,engine.server,revision,0,qq,RankingData.shortlist(qq,engine.scores,engine.charts()),engine.charts(),player.name,namedAt);
            RankingRepository.invalidate(context,engine);
        }finally{ScoreStore.write(marker,done);}
    }
}
