import cn.gmiya.malodylink.*;
import org.json.*;
import java.util.*;

/** Synthetic records only; never connects to or writes cloud storage. */
public final class RankingTest {
    static int checks;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);checks++;}
    static boolean near(double a,double b){return Math.abs(a-b)<1e-9;}
    static JSONObject chart(int cid,int sid,int level,boolean promote){return new JSONObject().put("cid",cid).put("sid",sid).put("level",level).put("server_hash","hash"+cid).put("title","Song "+sid).put("artist","Artist").put("bpm",180).put("creator","Mapper").put("version","Lv."+level).put("promote",promote);}
    static JSONObject score(int cid,int seed,double accuracy,int judge,int value){String hash=String.format(Locale.ROOT,"%032x",seed);return new JSONObject().put("id",cid+":"+(1700000000L+seed)+":"+hash).put("cid",cid).put("server_hash","hash"+cid).put("chart_md5",hash).put("played_at",1700000000L+seed).put("score_candidate",value).put("accuracy_candidate",accuracy).put("player","Fixture").put("raw_score_fields",new JSONObject().put("4",judge-1));}
    public static void main(String[] args)throws Exception {
        JSONArray charts=new JSONArray().put(chart(1,1,12,false)).put(chart(2,1,13,true)).put(chart(3,2,14,false));
        for(int raw=0;raw<=4;raw++){
            JSONObject sample=score(1,100+raw,99.8,raw+1,100);
            check(CloudRecords.judge(sample)==raw+1,"zero-based replay judge "+raw);
            RankingData.Player one=RankingData.player("123456",new JSONArray().put(sample),charts);
            check(near(one.best.get(1).rating,12*RatingRules.PRESET[raw+1]),"judge multiplier matches replay enum "+raw);
        }
        JSONObject omitted=score(1,200,99.8,1,100);omitted.getJSONObject("raw_score_fields").remove("4");omitted.getJSONObject("raw_score_fields").put("5",100);
        check(CloudRecords.judge(omitted)==1,"omitted protobuf zero enum means A");
        check(RatingRules.replayJudge(5)==0&&RatingRules.replayJudge(1.5)==0,"invalid and MAX enum excluded");
        SongCatalogue catalogue=new SongCatalogue(charts);
        check(catalogue.songs.size()==2&&catalogue.forChart(1)==catalogue.forChart(2),"group SID with separate chart difficulties");
        check(catalogue.search("ＡＲＴＩＳＴ mapper").size()==2,"NFKC case insensitive artist and mapper keywords");
        check(catalogue.search("missing").isEmpty(),"empty search results");
        check("180".equals(catalogue.forChart(1).bpm),"BPM metadata");
        check("Lv.13".equals(SongCatalogue.levelLabel(new JSONObject().put("level",32).put("version","SC9"))),"SC level display");
        check("Lv.12".equals(SongCatalogue.levelLabel(new JSONObject().put("version","Lv.24"))),"MaV display normalization");
        JSONArray records=new JSONArray().put(score(1,1,99.8,3,100)).put(score(2,2,99.8,3,200)).put(score(3,3,99.8,3,300));
        String original=records.toString();RankingData.Player p=RankingData.player("123456",records,charts);
        check(near(p.rating,27.0/30)&&near(p.oldRating,.7)&&near(p.newRating,1.3),"same song dedup and fixed O20 N10 total denominator");
        check(p.oldCount==1&&p.newCount==1&&p.best.size()==3,"single chart board preserves all difficulties");
        check(original.equals(records.toString()),"ranking does not mutate raw records");
        JSONArray many=new JSONArray();JSONArray all=new JSONArray();for(int i=1;i<=35;i++){all.put(chart(i,i,12,i>22));many.put(score(i,i,99.8,3,100));}RankingData.Player filled=RankingData.player("234567",many,all);
        check(filled.oldCount==20&&filled.newCount==10&&near(filled.rating,12),"top20 top10 truncation");
        JSONArray invalid=new JSONArray().put(score(1,1,99.8,3,100).put("server_hash","expired")).put(score(999,2,99.8,3,100));
        check(RankingData.player("345678",invalid,charts).best.isEmpty(),"stale or unknown charts excluded");
        JSONArray repeat=new JSONArray().put(score(1,1,99.8,3,999)).put(score(1,2,99.8,3,50)).put(score(1,3,99.9,3,40)).put(score(1,4,99.9,3,60));
        RankingData.Player q=RankingData.player("234567",repeat,charts);check(q.best.get(1).score==60,"rating then accuracy then raw score determines best record");
        RankingData.Player r=RankingData.player("345678",repeat,charts);RankingData.Player low=RankingData.player("456789",new JSONArray().put(score(1,1,99.8,3,100)),charts);
        List<RankingData.Score> ranked=RankingData.chart(Arrays.asList(q,r,low),1);check(ranked.get(0).rank==1&&ranked.get(1).rank==1&&ranked.get(2).rank==3,"shared ranks and competition gap");
        check(RankingData.chart(Arrays.asList(q,r),2).isEmpty(),"charts never mixed by song name");
        check(RankingData.total(Arrays.asList(low,r,q)).get(0).qq.equals("234567"),"deterministic total rating ordering");
        RankingData.Player restored=RankingData.decode(RankingData.encode(p));check(near(restored.rating,p.rating)&&restored.best.size()==3&&restored.best.get(2).judge==3,"aggregate snapshot round trip");
        check(RankingData.player("567890",new JSONArray().put(score(1,1,99.8,0,100)),charts).best.isEmpty(),"unknown judge excluded from single chart board");
        System.out.println("PASS: "+checks+" ranking/catalogue checks");
    }
}
