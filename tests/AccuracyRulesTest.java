import cn.gmiya.malodylink.*;
import org.json.*;

public final class AccuracyRulesTest {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static JSONObject chart(){return new JSONObject().put("judgement_count",2496).put("judgement_rule",AccuracyRules.RULE);}
    static JSONObject record(double accuracy,int best,int cool,int good,int miss){return new JSONObject().put("accuracy_candidate",accuracy).put("raw_score_fields",new JSONObject().put("7",best).put("8",cool).put("9",good).put("10",miss));}
    public static void main(String[] args)throws Exception{
        JSONObject quit=record(100,1,0,0,0);String original=quit.toString();
        check(Math.abs(AccuracyRules.whole(quit,chart())-100.0/2496)<1e-12,"one hit cannot be whole-chart 100%");
        check(quit.toString().equals(original),"original record retained");
        check(AccuracyRules.whole(record(99.21951293945312,4794,326,0,2),chart())==99.21951293945312,"known completed Alpharise result preserved");
        JSONObject other=new JSONObject().put("judgement_count",6093).put("judgement_rule",AccuracyRules.RULE);
        check(AccuracyRules.whole(record(98.42031860351562,5374,693,4,7),other)==98.42031860351562,"known completed hold-heavy chart preserved");
        check(AccuracyRules.whole(record(100,2496,0,0,0),chart())==100,"full perfect preserved");
        check(AccuracyRules.whole(record(100,0,0,0,2496),chart())==0,"all misses zero");
        check(AccuracyRules.whole(quit,new JSONObject())==100,"unknown profiles do not borrow wrong capacity");
        check(Double.isNaN(AccuracyRules.whole(record(100,-1,0,0,0),chart())),"invalid counts rejected");
        check(AccuracyRules.whole(new JSONObject().put("accuracy_candidate",98),chart())==98,"legacy missing counts preserved");
        System.out.println("PASS: whole-chart cap, raw preservation, completed records, missing and invalid fields");
    }
}
