package cn.gmiya.malodylink;

import org.json.*;

/** Whole-chart upper bound, preserving the original record and finer skin judgements. */
public final class AccuracyRules {
    public static final String RULE="slide-whole-accuracy-upper-v1";
    public static double whole(JSONObject record,JSONObject chart){
        double original=record.optDouble("accuracy_candidate");
        if(!Double.isFinite(original)||original<0||original>100)return Double.NaN;
        if(!RULE.equals(chart.optString("judgement_rule")))return original;
        long capacity=chart.optLong("judgement_count");
        if(capacity<=0||capacity>10000000)return original;
        JSONObject raw=record.optJSONObject("raw_score_fields");
        if(raw==null)return original;
        boolean present=false;double[] counts=new double[4];
        for(int i=0;i<4;i++){
            String key=Integer.toString(i+7);if(!raw.has(key))continue;present=true;
            Object v=raw.opt(key);if(!(v instanceof Number))return Double.NaN;
            double n=((Number)v).doubleValue();if(!Double.isFinite(n)||n<0||n!=Math.rint(n)||n>10000000)return Double.NaN;
            counts[i]=n;
        }
        if(!present)return original; // Older formats cannot be reconstructed; do not invent counts.
        // Counts do not identify the skin or finer grades. Each successful judgement contributes at most 100%.
        // Capacity is the minimum confirmed chart capacity across compatible skin profiles.
        double upper=100*(counts[0]+counts[1]+counts[2])/capacity;
        return Math.min(original,Math.max(0,Math.min(100,upper)));
    }
}
