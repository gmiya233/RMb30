package cn.gmiya.malodylink;

import org.json.*;
import java.math.BigDecimal;
import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;

public final class ChartMatcher {
    /** Catalogue identity only; intentionally does not compare note/time contents. */
    public static boolean catalogMatches(byte[] local,String basename,JSONObject candidate)throws Exception {
        if(!basename.equals(candidate.optString("mc_name")))return false;
        JSONObject meta=new JSONObject(new String(local,StandardCharsets.UTF_8)).optJSONObject("meta");
        if(meta==null||!meta.has("mode")||meta.optInt("mode",-1)!=candidate.optInt("mode",-2))return false;
        JSONObject song=meta.optJSONObject("song");if(song==null)return false;
        Matcher folder=Pattern.compile("(?:^|/)_song_(\\d+)(?:/|$)").matcher(candidate.optString("path"));
        if(folder.find()&&song.optLong("id",0)>0)return Long.parseLong(folder.group(1))==song.optLong("id");
        String title=song.optString("title").trim(),expected=candidate.optString("title").trim();
        return !title.isEmpty()&&!expected.isEmpty()&&title.equalsIgnoreCase(expected);
    }
    public static String md5(byte[] data)throws Exception {
        StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("MD5").digest(data))out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();
    }
    public static String canonical(Object v)throws JSONException {
        if(v==null||v==JSONObject.NULL)return "null";
        if(v instanceof JSONObject){JSONObject o=(JSONObject)v;List<String> keys=new ArrayList<>();Iterator<String> it=o.keys();while(it.hasNext())keys.add(it.next());Collections.sort(keys);
            StringBuilder s=new StringBuilder("{");for(String k:keys){if(s.length()>1)s.append(',');s.append(JSONObject.quote(k)).append(':').append(canonical(o.get(k)));}return s.append('}').toString();}
        if(v instanceof JSONArray){JSONArray a=(JSONArray)v;StringBuilder s=new StringBuilder("[");for(int i=0;i<a.length();i++){if(i>0)s.append(',');s.append(canonical(a.get(i)));}return s.append(']').toString();}
        if(v instanceof Number)return new BigDecimal(v.toString()).stripTrailingZeros().toPlainString();
        if(v instanceof Boolean)return v.toString();return JSONObject.quote(v.toString());
    }
    public static String gameplay(JSONObject chart)throws JSONException {
        JSONObject result=new JSONObject(),meta=chart.optJSONObject("meta");
        if(meta==null||!chart.has("note")||!chart.has("time"))throw new JSONException("谱面缺少关键数据");
        result.put("mode",meta.opt("mode"));result.put("mode_ext",meta.optJSONObject("mode_ext")==null?new JSONObject():meta.get("mode_ext"));
        // Every top-level field except cosmetic/editor metadata participates in comparison.
        Iterator<String> keys=chart.keys();while(keys.hasNext()){String k=keys.next();if(!k.equals("meta")&&!k.equals("extra"))result.put(k,chart.get(k));}
        return canonical(result);
    }
    public static boolean matches(byte[] local,byte[] server)throws Exception {
        return gameplay(new JSONObject(new String(local,StandardCharsets.UTF_8))).equals(gameplay(new JSONObject(new String(server,StandardCharsets.UTF_8))));
    }
}
