package cn.gmiya.malodylink;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Skip decisions depend on matching identities, not cloud record order or display metadata. */
public final class ScanCache {
    private JSONObject files;
    public ScanCache(JSONObject saved,String revision,JSONArray scores)throws Exception {
        boolean valid=saved.optInt("schema")==1&&revision.equals(saved.optString("revision"));
        if(valid&&scores!=null){
            Set<String> current=scoreKeys(scores);JSONArray required=saved.optJSONArray("scores");
            if(required==null)valid=false;
            else for(int i=0;i<required.length();i++)if(!current.contains(required.getString(i))){valid=false;break;}
        }
        files=valid?saved.optJSONObject("files"):null;if(files==null)files=new JSONObject();
    }
    public boolean unchanged(String path,String stamp){return !stamp.isEmpty()&&stamp.equals(files.optString(path));}
    public void checked(String path,String stamp)throws Exception {if(!stamp.isEmpty())files.put(path,stamp);}
    public JSONObject snapshot(String revision,JSONArray scores,List<String> paths)throws Exception {
        JSONObject present=new JSONObject();for(String path:paths)if(files.has(path))present.put(path,files.get(path));
        JSONObject result=new JSONObject().put("schema",1).put("revision",revision).put("files",present);
        if(scores!=null){JSONArray keys=new JSONArray();for(String key:scoreKeys(scores))keys.put(key);result.put("scores",keys);}
        return result;
    }
    private static Set<String> scoreKeys(JSONArray scores)throws Exception {
        Set<String> keys=new TreeSet<>();for(int i=0;i<scores.length();i++){
            JSONObject score=scores.getJSONObject(i);keys.add(new JSONArray().put(score.optString("id")).put(score.optString("server_hash")).toString());
        }return keys;
    }
    public static String revision(String server,JSONArray charts,JSONObject aliases)throws Exception {
        Set<String> rows=new TreeSet<>();for(int i=0;i<charts.length();i++){
            JSONObject chart=charts.getJSONObject(i);JSONArray row=new JSONArray();
            for(String key:new String[]{"cid","server_hash","mc_name","mode","path","title"})row.put(chart.optString(key));
            rows.add(row.toString());
        }
        JSONArray chartKeys=new JSONArray();for(String row:rows)chartKeys.put(row);
        JSONObject aliasKeys=new JSONObject();Iterator<String> it=aliases.keys();while(it.hasNext()){
            String key=it.next();JSONObject alias=aliases.getJSONObject(key);
            aliasKeys.put(key,new JSONArray().put(alias.optInt("cid")).put(alias.optString("server_hash")));
        }
        return ChartMatcher.md5(ChartMatcher.canonical(new JSONArray().put(server).put(chartKeys).put(aliasKeys)).getBytes(StandardCharsets.UTF_8));
    }
}
