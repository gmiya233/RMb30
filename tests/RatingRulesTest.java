import cn.gmiya.malodylink.*;
import org.json.*;
import java.nio.file.*;
import java.util.*;

/** Approved rule anchors, cross-platform namespace vectors and history reselection. */
public final class RatingRulesTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);System.out.println("PASS: "+message);}
    static boolean near(double a,double b){return Math.abs(a-b)<1e-9;}
    public static void main(String[] args)throws Exception {
        RatingRules rules=new RatingRules();double[] caps={0,13.58,13.79,14,14.28,14.56};
        for(int j=1;j<=5;j++)check(near(rules.rating(14,"",99.8,j),caps[j])&&near(rules.rating(14,"",100,j),caps[j]),"14-level judge "+j+" has approved 99.8/100 anchor");
        for(int level=1;level<=15;level++)check(near(rules.rating(level,"",99.8,3),level),"C 99.8 equals normalized level "+level);
        check(rules.rating(14,"",99,1)>rules.rating(14,"",95,5),"A99 outranks E95 on the same level-14 chart");
        check(rules.rating(14,"",95,5)>rules.rating(14,"",96.5,3)&&rules.rating(14,"",95,5)<rules.rating(14,"",97,3),"E95 falls between C96.5 and C97");
        check(near(RatingRules.accuracyFactor(99.5),1/1.01)&&near(RatingRules.accuracyFactor(99.65),1.005/1.01),"full original curve is normalized, including its middle segment");
        double previous=-1;for(int i=0;i<=100000;i++){double n=RatingRules.accuracyFactor(i/1000.0);checkMonotonic(n,previous);previous=n;}
        for(double boundary:new double[]{99.5,99.8})check(Math.abs(RatingRules.accuracyFactor(boundary)-RatingRules.accuracyFactor(boundary-1e-7))<1e-7,"accuracy curve continuous at "+boundary);
        double[] old={Double.NaN,.78,.85,1,1.1,1.2};check(near(RatingRules.migratePreview(old)[1],.97)&&old[1]==.78,"old saved preset migrates without mutating its source");
        double[] custom={Double.NaN,.91,.96,1,1.03,1.07};check(near(RatingRules.migratePreview(custom)[1],.91),"custom preview multipliers are preserved");
        JSONArray charts=new JSONArray().put(RankingTest.chart(1,1,14,false));
        JSONArray histories=new JSONArray().put(RankingTest.score(1,1,95,5,100)).put(RankingTest.score(1,2,99,1,90));String raw=histories.toString();
        RankingData.Player rebuilt=RankingData.player("123456",histories,charts);
        check(rebuilt.best.get(1).judge==1,"rebuilding full history can replace the old E95 best with A99");
        check(raw.equals(histories.toString()),"rating migration preserves every raw history field");
        JSONArray vectors=new JSONArray(Files.readString(Paths.get("ios/Tests/MalodyCoreTests/Fixtures/revisions.json")));
        for(int i=0;i<vectors.length();i++){JSONObject vector=vectors.getJSONObject(i),catalogue=vector.getJSONObject("catalogue");check(vector.getString("revision").equals(RankingIndex.revision(catalogue.getString("server"),catalogue.getJSONArray("charts"))),"Android/server and Swift namespace fixture "+i+" agree");}
    }
    static void checkMonotonic(double n,double previous){if(n+1e-12<previous)throw new AssertionError("accuracy decreased");}
}
