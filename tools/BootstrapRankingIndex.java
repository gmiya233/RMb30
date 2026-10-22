import cn.gmiya.malodylink.*;
import org.json.*;
import java.nio.file.*;
import java.util.*;

/** Explicit administrator migration: reads original histories; writes derived indices only. */
public final class BootstrapRankingIndex {
    public static void main(String[] args)throws Exception {
        JSONObject config=new JSONObject(Files.readString(Paths.get(args[0]))),catalogue=new JSONObject(Files.readString(Paths.get(args[1])));String server=config.getString("server");if(!server.equals(catalogue.getString("server")))throw new IllegalArgumentException("server mismatch");JSONArray charts=catalogue.getJSONArray("charts");String revision=RankingIndex.revision(server,charts);
        OpenListScoreRemote source=new OpenListScoreRemote(server,config.getString("username"),config.getString("password"),config.getString("root"));RankingIndex.Remote remote=new OpenListRankingRemote(source);int players=0,singles=0;long bytes=0;
        for(String qq:source.players()){List<String> originals=source.list(qq,false);CloudArchive.Result history=CloudArchive.sync(source,qq,server,new JSONArray(),charts,false,p->{});RankingData.Player player=RankingData.player(qq,history.merged.scores,charts);long namedAt=0;for(int i=0;i<history.merged.scores.length();i++)namedAt=Math.max(namedAt,history.merged.scores.getJSONObject(i).optLong("played_at"));
            for(RankingData.Score score:player.best.values()){RankingIndex.publish(remote,server,revision,score.cid,qq,new JSONArray().put(score.record),charts,player.name,namedAt);singles++;}RankingIndex.publish(remote,server,revision,0,qq,RankingData.shortlist(qq,history.merged.scores,charts),charts,player.name,namedAt);
            if(!originals.equals(source.list(qq,false)))throw new IllegalStateException("History changed during migration; re-run to incorporate concurrent uploads");RankingIndex.Heads heads=RankingIndex.read(remote,server,revision,0,qq,charts);RankingData.Player indexed=RankingIndex.player(qq,heads,charts);if(Math.abs(player.rating-indexed.rating)>1e-9)throw new AssertionError("Index total mismatch");for(String name:heads.names)bytes+=remote.get(RankingIndex.folder(revision,0,qq),name).length;players++;
        }
        System.out.println("PASS: derived index migration; players="+players+", chart rows="+singles+", RT index bytes="+bytes+"; original history file listings unchanged.");
    }
}
