import cn.gmiya.malodylink.LocalPlayerProfile;
import org.json.*;
import java.util.*;

public final class PlayerTitleTest {
    private static JSONObject chart(int cid,int sid,String title){return new JSONObject().put("cid",cid).put("sid",sid).put("title",title).put("server_hash","current");}
    private static JSONObject score(int cid,double accuracy){return new JSONObject().put("cid",cid).put("accuracy_candidate",accuracy).put("server_hash","current");}
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);System.out.println("PASS: "+label);}
    public static void main(String[] args){
        check("小师妹".equals(LocalPlayerProfile.ratingTitle(0))&&"小师妹".equals(LocalPlayerProfile.ratingTitle(9.9999)),"default title below rating10");
        check("萌新".equals(LocalPlayerProfile.ratingTitle(10))&&"萌新".equals(LocalPlayerProfile.ratingTitle(10.9999)),"rating10 unlocks novice");
        check("高手".equals(LocalPlayerProfile.ratingTitle(11))&&"大师".equals(LocalPlayerProfile.ratingTitle(12))&&"龙币".equals(LocalPlayerProfile.ratingTitle(13))&&"龙币".equals(LocalPlayerProfile.ratingTitle(14)),"rating11/12/13 thresholds and highest tier");
        check(LocalPlayerProfile.ratingTitles(12).size()==4&&!LocalPlayerProfile.ratingTitles(12).containsKey("rank:13"),"only earned rating titles selectable, earlier titles retained");
        JSONArray charts=new JSONArray().put(chart(1,10,"Boundary")).put(chart(2,20,"Eligible")).put(chart(3,20,"Eligible")).put(chart(4,40,"Stale")).put(chart(5,50,"Invalid")).put(chart(6,60,"Demo")).put(chart(7,70,"Manual"));
        JSONArray scores=new JSONArray().put(score(1,99.8)).put(score(2,99.8001)).put(score(3,100)).put(score(4,100).put("server_hash","old")).put(score(5,100.1)).put(score(999,100)).put(score(6,100).put("demo",true)).put(score(7,100).put("manual",true));
        String original=scores.toString();SortedMap<String,String> eligible=LocalPlayerProfile.eligibleTitles(charts,scores);
        check(eligible.size()==1&&"Eligible".equals(eligible.get("sid:20")),"strict >99.8, current catalogue, valid accuracy, unique song, no preview records");
        check(original.equals(scores.toString()),"title selection never mutates raw scores");
        check(LocalPlayerProfile.eligibleTitles(charts,new JSONArray()).isEmpty(),"empty histories unlock nothing");
        check(LocalPlayerProfile.eligibleTitles(new JSONArray(),scores).isEmpty(),"removed charts unlock nothing");
    }
}
