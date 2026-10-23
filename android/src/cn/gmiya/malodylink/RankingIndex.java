package cn.gmiya.malodylink;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable, mergeable per-player indices. Never overwrites a shared leaderboard file. */
public final class RankingIndex {
    public static final int MAX_BYTES=128*1024,MAX_HEADS=256;
    public interface Remote {
        List<String> directories(String path)throws Exception;
        List<String> files(String path)throws Exception;
        byte[] get(String path,String name)throws Exception;
        void put(String path,String name,byte[] bytes)throws Exception;
        void remove(String path,List<String> observedNames)throws Exception;
    }
    public static boolean name(String value){return value!=null&&value.matches("entry-[a-f0-9]{64}\\.json");}
    public static String revision(String server,JSONArray charts)throws Exception {
        List<JSONObject> sorted=new ArrayList<>();for(int i=0;i<charts.length();i++)sorted.add(charts.getJSONObject(i));sorted.sort((a,b)->Integer.compare(a.optInt("cid"),b.optInt("cid")));
        // Android and desktop org.json escape forward slashes differently. The namespace
        // must use a platform-independent encoding, rather than JSON serializer output.
        StringBuilder key=new StringBuilder("index-v2-zero-based-judge:");part(key,server);
        for(JSONObject c:sorted){part(key,Integer.toString(c.optInt("cid")));part(key,Integer.toString(c.optInt("sid")));part(key,c.optString("server_hash"));part(key,c.optString("version"));double level=c.optDouble("level");part(key,Double.isFinite(level)?java.math.BigDecimal.valueOf(level).stripTrailingZeros().toPlainString():"?");part(key,(c.optBoolean("promote")||c.optInt("promote")==1)?"1":"0");part(key,c.optString("path"));}
        return CloudRecords.sha256(key.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void part(StringBuilder key,String value){key.append(value.length()).append(':').append(value);}
    public static String board(String revision,int cid){if(!revision.matches("[a-f0-9]{64}")||cid<0)throw new IllegalArgumentException("无效排行索引");return "/_rankings/v1/"+revision+(cid==0?"/rt":"/charts/"+cid);}
    public static String folder(String revision,int cid,String qq){return board(revision,cid)+"/"+CloudRecords.qq(qq);}
    public static final class Heads {public final List<String> names=new ArrayList<>();public JSONArray scores=new JSONArray();public String player="";public long namedAt=-1;}
    public static Heads read(Remote remote,String server,String revision,int cid,String qq,JSONArray charts)throws Exception {
        String path=folder(revision,cid,qq);Exception last=null;
        // Another device can compact observed heads between listing and GET; re-list once.
        for(int attempt=0;attempt<2;attempt++){try{Heads result=new Heads();List<String> names=remote.files(path);if(names.size()>MAX_HEADS)throw new IOException("排行索引分片过多，请先同步该玩家成绩");
            for(String name:names){if(!name(name))continue;byte[] data=remote.get(path,name);if(data.length>MAX_BYTES||!name.equals("entry-"+CloudRecords.sha256(data)+".json"))throw new IOException("排行索引校验失败");JSONObject doc=new JSONObject(new String(data,StandardCharsets.UTF_8));if(doc.optInt("schema")!=1||!server.equals(doc.optString("server"))||!revision.equals(doc.optString("revision"))||!qq.equals(doc.optString("qq"))||doc.optInt("cid",-1)!=cid)throw new IOException("排行索引不匹配");JSONArray scores=doc.getJSONArray("scores");if(scores.length()>(cid==0?30:1))throw new IOException("排行摘要超过容量");for(int i=0;i<scores.length();i++){JSONObject score=scores.getJSONObject(i);if(cid!=0&&score.optInt("cid")!=cid)throw new IOException("单曲排行谱面不匹配");result.scores.put(score);}String player=doc.optString("player");long at=doc.optLong("named_at",-1);if(player.length()>128)throw new IOException("玩家名称过长");if(at>result.namedAt||(at==result.namedAt&&player.compareTo(result.player)<0)){result.player=player;result.namedAt=at;}result.names.add(name);}
            result.scores=CloudRecords.merge(new JSONArray(),result.scores,new CloudRecords.Catalogue(charts)).scores;return result;
        }catch(Exception race){last=race;}}
        throw last;
    }
    public static RankingData.Player player(String qq,Heads heads,JSONArray charts)throws Exception {RankingData.Player player=RankingData.player(qq,heads.scores,charts);if(!heads.player.trim().isEmpty()){player.name=heads.player;for(RankingData.Score score:player.best.values())score.name=player.name;}return player;}
    public static JSONObject document(String server,String revision,int cid,String qq,JSONArray scores,String player,long namedAt)throws Exception {return new JSONObject().put("schema",1).put("server",server).put("revision",revision).put("cid",cid).put("qq",qq).put("scores",scores).put("player",player).put("named_at",namedAt);}
    public static boolean publish(Remote remote,String server,String revision,int cid,String qq,JSONArray incoming,JSONArray charts,String player,long namedAt)throws Exception {
        String path=folder(revision,cid,qq);Heads previous=read(remote,server,revision,cid,qq,charts);JSONArray union=CloudRecords.merge(incoming,previous.scores,new CloudRecords.Catalogue(charts)).scores;
        if(previous.namedAt>namedAt||(previous.namedAt==namedAt&&previous.player.compareTo(player)<0)){player=previous.player;namedAt=previous.namedAt;}
        JSONArray selected;if(cid==0)selected=RankingData.shortlist(qq,union,charts);else{RankingData.Score best=RankingData.player(qq,union,charts).best.get(cid);selected=new JSONArray();if(best!=null)selected.put(best.record);}if(selected.length()==0)return false;
        RankingData.Player calculated=RankingData.player(qq,selected,charts);JSONObject doc=document(server,revision,cid,qq,selected,player,namedAt);
        if(cid==0)doc.put("rating",calculated.rating).put("o20_rating",calculated.oldRating).put("n10_rating",calculated.newRating);
        else doc.put("rating",calculated.best.get(cid).rating);
        byte[] bytes=ChartMatcher.canonical(doc).getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_BYTES)throw new IOException("排行摘要过大");String name="entry-"+CloudRecords.sha256(bytes)+".json";
        boolean created=!previous.names.contains(name);if(created){remote.put(path,name,bytes);if(!Arrays.equals(bytes,remote.get(path,name)))throw new IOException("排行索引回读未完成，下次同步重试");}
        List<String> obsolete=new ArrayList<>(previous.names);obsolete.remove(name);
        // Only remove the exact heads observed and included before publication. Concurrent
        // unseen heads remain, and readers union their top-K candidates without lost updates.
        if(!obsolete.isEmpty())try{remote.remove(path,obsolete);}catch(Exception keepOldHeads){}
        return created;
    }
}
