import cn.gmiya.malodylink.*;
import org.json.*;
import java.util.*;

public final class RecentSearchTest {
    static void check(boolean b,String name){if(!b)throw new AssertionError(name);}
    public static void main(String[] args)throws Exception {
        JSONArray charts=new JSONArray().put(RankingTest.chart(1,1,24,false)).put(RankingTest.chart(2,2,32,false).put("version","SC9")).put(RankingTest.chart(3,1,12,false));
        JSONArray records=new JSONArray();for(int i=1;i<=75;i++)records.put(RankingTest.score(1,i,99.5,3,i));
        records.put(RankingTest.score(999,99,99.5,3,1));records.put(RankingTest.score(1,100,99.5,3,1).put("server_hash","expired"));
        String original=records.toString();List<RecentPlays.Play> recent=RecentPlays.load(records,charts);
        check(recent.size()==50,"50 valid plays");check(recent.get(0).record.getInt("score_candidate")==75&&recent.get(49).record.getInt("score_candidate")==26,"latest first, duplicate songs retained");
        check(recent.get(0).level==12,"display normalized level");check(original.equals(records.toString()),"raw history unchanged");
        SongCatalogue c=new SongCatalogue(charts);check(c.search("１２",SongCatalogue.LEVEL).size()==1,"normalized grade with Unicode input and same-song dedup");
        check(c.search("13",SongCatalogue.LEVEL).size()==1,"SC display grade");check(c.search("24",SongCatalogue.LEVEL).isEmpty(),"search display rather than raw grade");
        for(String bad:new String[]{"NaN","Infinity","-1","text"})check(c.search(bad,SongCatalogue.LEVEL).isEmpty(),"reject invalid grade");
        check(c.search("Song 1",SongCatalogue.TITLE).size()==1,"title field filter");check(c.search("Mapper",SongCatalogue.CREATOR).size()==2,"mapper field filter");
        System.out.println("PASS: recent 50, duplicates, order, catalogue filtering and displayed grade search");
    }
}
