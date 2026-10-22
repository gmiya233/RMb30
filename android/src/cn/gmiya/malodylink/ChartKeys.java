package cn.gmiya.malodylink;

import org.json.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Descriptive metadata only; never participates in score identity or rating. */
public final class ChartKeys {
    private static final Pattern VERSION=Pattern.compile("(?i)(?<![0-9])([1-9][0-9]?)\\s*(?:k(?![a-z])|键)");
    public static int count(JSONObject chart){
        if("conflicting_metadata".equals(chart.optString("keys_source")))return 0;
        int keys=chart.optInt("keys");if(keys>0&&keys<=64)return keys;
        JSONObject ext=chart.optJSONObject("mode_ext");if(chart.optInt("mode",-1)==0&&ext!=null){keys=ext.optInt("column");if(keys>0&&keys<=64)return keys;}
        Matcher m=VERSION.matcher(Normalizer.normalize(chart.optString("version"),Normalizer.Form.NFKC));Set<Integer> values=new HashSet<>();while(m.find())values.add(Integer.parseInt(m.group(1)));return values.size()==1?values.iterator().next():0;
    }
    public static String label(JSONObject chart){int count=count(chart);return count>0?count+"键":"键数待识别";}
    public static void enrich(JSONArray charts,JSONObject supplement,String server)throws Exception {
        if(supplement.optInt("schema")!=1||!server.equals(supplement.optString("server")))return;
        JSONArray rows=supplement.getJSONArray("charts");if(rows.length()>100000)throw new IllegalArgumentException("Key metadata too large");
        Map<Integer,JSONObject> byId=new HashMap<>();for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);byId.put(row.getInt("cid"),row);}
        for(int i=0;i<charts.length();i++){JSONObject chart=charts.getJSONObject(i),row=byId.get(chart.optInt("cid"));if(row!=null&&chart.optString("server_hash").equals(row.optString("server_hash"))){int keys=row.optInt("keys");if(keys>=0&&keys<=64){chart.put("keys",keys);chart.put("keys_source",row.optString("keys_source"));}}}
    }
}
