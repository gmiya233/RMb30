import cn.gmiya.malodylink.ChartMatcher;
import org.json.*;
import java.nio.charset.StandardCharsets;

public final class CatalogMatchTest {
    private static final JSONObject candidate=new JSONObject().put("mc_name","1763538149.mc").put("path","charts_tagged/IN/_song_28201/0").put("mode",7).put("title","Song");
    private static byte[] chart(int mode,int songId,String title,int column){return ("{\"meta\":{\"mode\":"+mode+",\"song\":{\"id\":"+songId+",\"title\":\""+title+"\"}},\"note\":[{\"column\":"+column+"}]}").getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean ok,String s){if(!ok)throw new AssertionError(s);System.out.println("PASS: "+s);}
    public static void main(String[] args)throws Exception {
        check(ChartMatcher.catalogMatches(chart(7,28201,"Renamed",1),"1763538149.mc",candidate),"filename, mode and song identity match despite cosmetic title changes");
        check(ChartMatcher.catalogMatches(chart(7,28201,"Renamed",5),"1763538149.mc",candidate),"note content deliberately excluded by user choice");
        check(!ChartMatcher.catalogMatches(chart(7,28201,"Song",1),"unknown.mc",candidate),"unknown filename rejected");
        check(!ChartMatcher.catalogMatches(chart(0,28201,"Song",1),"1763538149.mc",candidate),"different mode rejected");
        check(!ChartMatcher.catalogMatches(chart(7,999,"Song",1),"1763538149.mc",candidate),"different known song ID rejected even with same title");
        check(ChartMatcher.catalogMatches(chart(7,0,"Song",1),"1763538149.mc",candidate),"title fallback when source song ID is absent");
        check(!ChartMatcher.catalogMatches(chart(7,0,"Other",1),"1763538149.mc",candidate),"missing identity and different title rejected");
    }
}
