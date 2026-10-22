import cn.gmiya.malodylink.*;
import org.json.*;

public final class ChartKeysTest {
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        for(String version:new String[]{"RM 4K Hard Lv.12","Ver_4k_hd_Lv.13","４键 Lv.12"})check(ChartKeys.count(new JSONObject().put("version",version))==4,"version key count");
        check(ChartKeys.count(new JSONObject().put("version","Lv.14"))==0,"grade is not key count");
        check(ChartKeys.count(new JSONObject().put("mode",7).put("mode_ext",new JSONObject().put("column",0)))==0,"slide column zero is not a count");
        check(ChartKeys.count(new JSONObject().put("mode",0).put("mode_ext",new JSONObject().put("column",7)))==7,"native key mode uses declared columns");
        JSONObject chart=RankingTest.chart(1,1,12,false);JSONArray rows=new JSONArray().put(chart);String revision=RankingIndex.revision("fixture",rows);
        JSONObject meta=new JSONObject().put("schema",1).put("server","fixture").put("charts",new JSONArray().put(new JSONObject().put("cid",1).put("server_hash","expired").put("keys",6)));
        ChartKeys.enrich(rows,meta,"fixture");check(ChartKeys.count(chart)==0,"stale hash ignored");meta.getJSONArray("charts").getJSONObject(0).put("server_hash","hash1");ChartKeys.enrich(rows,meta,"fixture");check(ChartKeys.count(chart)==6,"matching metadata applied");
        check(revision.equals(RankingIndex.revision("fixture",rows)),"key metadata cannot invalidate existing rankings");
        check(ChartKeys.count(new JSONObject().put("version","4K 6K"))==0,"ambiguous declarations stay unknown");
        check(ChartKeys.count(new JSONObject().put("version","4K").put("keys_source","conflicting_metadata"))==0,"conflicting metadata stays unknown");
        JSONArray mixed=new JSONArray().put(RankingTest.chart(10,10,12,false).put("keys",4))
            .put(RankingTest.chart(11,10,13,false).put("keys",6)).put(RankingTest.chart(12,10,14,false).put("keys",6))
            .put(RankingTest.chart(20,20,6,false).put("version","Lv.6"))
            .put(RankingTest.chart(30,30,12,false).put("version","RM 5K Lv.12"));
        SongCatalogue songs=new SongCatalogue(mixed);String unchanged=mixed.toString();
        for(String query:new String[]{"6","6K","６ｋ","6键"," 6 键 "})check(songs.search(query,SongCatalogue.KEYS).size()==1,"key count search retains songs once across multiple matching difficulties");
        check(songs.search("4",SongCatalogue.KEYS).get(0)==songs.forChart(11),"one song can match each supported key layout");
        check(songs.search("5",SongCatalogue.KEYS).get(0)==songs.forChart(30),"search uses same version fallback as badges");
        for(String query:new String[]{"0","65","-4","4.5","4K 6K","Lv.6"})check(songs.search(query,SongCatalogue.KEYS).isEmpty(),"invalid or ambiguous key count excluded");
        check(unchanged.equals(mixed.toString()),"key search preserves all chart data");
        JSONArray graded=new JSONArray()
            .put(RankingTest.chart(40,40,0,false).put("version","Unrated").put("keys",4))
            .put(RankingTest.chart(41,40,12,false).put("keys",6))
            .put(RankingTest.chart(50,50,0,false).put("version","Unrated").put("keys",6))
            .put(RankingTest.chart(60,60,12,false).put("keys",4));
        SongCatalogue filtered=new SongCatalogue(graded);
        check(filtered.search("",SongCatalogue.ALL,true).size()==2,"only songs with at least one provided display grade remain");
        check(filtered.search("",SongCatalogue.ALL,false).size()==3,"unchecking restores ungraded songs");
        check(filtered.search("4",SongCatalogue.KEYS,true).size()==1&&filtered.search("4",SongCatalogue.KEYS,true).get(0)==filtered.forChart(60),"key and grade must match on the same difficulty");
        check(filtered.search("4",SongCatalogue.KEYS,false).size()==2,"key search without checkbox keeps ungraded layouts");
        check(SongCatalogue.matchingCharts(filtered.forChart(40),"",SongCatalogue.ALL,true).size()==1,"card summaries omit ungraded difficulties when enabled");
        check(SongCatalogue.matchingCharts(filtered.forChart(40),"12",SongCatalogue.LEVEL,false).get(0).getInt("cid")==41,"level search summary selects matching difficulty");
        check("Lv.12".equals(SongCatalogue.highestLevelLabel(filtered.forChart(40).charts)),"highest grade ignores ungraded difficulties");
        check("未定级".equals(SongCatalogue.highestLevelLabel(SongCatalogue.matchingCharts(filtered.forChart(40),"4",SongCatalogue.KEYS,false))),"highest grade respects selected keys");
        check("Lv.15".equals(SongCatalogue.highestLevelLabel(java.util.Arrays.asList(RankingTest.chart(70,70,24,false),RankingTest.chart(71,70,15,false)))),"highest grade compares displayed rather than raw levels");
        System.out.println("PASS: key metadata, version fallback, slide semantics, stale identity protection and rating namespace stability");
    }
}
