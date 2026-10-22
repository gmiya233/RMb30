package cn.gmiya.malodylink;

import org.json.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Validates a single server-aggregated board before it enters the local cache. */
public final class RankingSnapshot {
    public static final int MAX_BYTES=16*1024*1024;
    public final List<RankingData.Player> players=new ArrayList<>();
    public long updated;public int failed,folders;
    public static String path(String revision,int cid){
        if(!revision.matches("[a-f0-9]{64}")||cid<0)throw new IllegalArgumentException("Invalid board");
        return "/_rankings/v2/"+revision+(cid==0?"/rt.json":"/charts/"+cid+".json");
    }
    public static RankingSnapshot read(byte[] bytes,String server,String revision,int cid)throws Exception {
        if(bytes.length>MAX_BYTES)throw new IOException("排行摘要超过容量");
        JSONObject doc=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(doc.optInt("schema")!=2||!server.equals(doc.optString("server"))||!revision.equals(doc.optString("revision"))||doc.optInt("cid",-1)!=cid)throw new IOException("排行摘要不匹配");
        RankingSnapshot result=new RankingSnapshot();result.updated=doc.getLong("updated");result.failed=doc.getInt("failed");result.folders=doc.getInt("folders");
        if(result.updated<=0||result.updated>System.currentTimeMillis()+300000||result.failed<0||result.folders<0||result.failed>result.folders)throw new IOException("排行状态无效");
        JSONArray rows=doc.getJSONArray("players");if(rows.length()>10000||rows.length()>result.folders)throw new IOException("排行人数超过容量");
        Set<String> ids=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);RankingData.Player p=RankingData.decode(row);
            if(!ids.add(p.qq)||p.name.length()>128||!finite(p.rating)||!finite(p.oldRating)||!finite(p.newRating)||p.oldCount<0||p.oldCount>20||p.newCount<0||p.newCount>10)throw new IOException("玩家排行数据无效");
            JSONArray best=row.getJSONArray("best");if(best.length()!=(cid==0?0:1))throw new IOException("单曲排行摘要无效");
            for(RankingData.Score s:p.best.values())if(s.cid!=cid||!finite(s.rating)||!finite(s.accuracy)||s.accuracy>101||s.score<0||s.played<0)throw new IOException("单曲排行数据无效");
            result.players.add(p);
        }return result;
    }
    private static boolean finite(double n){return Double.isFinite(n)&&n>=0;}
}
