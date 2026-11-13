package cn.gmiya.malodylink;

import org.json.*;
import java.util.*;

/** A replay depends on its own matched chart and score, never the entire catalogue. */
public final class ReplayScanCache {
    private final String server,revision;private final JSONObject files=new JSONObject();
    private final Map<String,String> bindings=new HashMap<>();private final Set<String> records=new HashSet<>();
    public ReplayScanCache(JSONObject saved,String server,JSONArray charts,JSONObject aliases,JSONArray scores)throws Exception {
        this.server=server;revision=ScanCache.revision(server,charts,aliases);
        Set<String> active=new HashSet<>();for(int i=0;i<charts.length();i++){JSONObject c=charts.getJSONObject(i);active.add(c.optInt("cid")+":"+c.optString("server_hash"));}
        Iterator<String> keys=aliases.keys();while(keys.hasNext()){String hash=keys.next();JSONObject a=aliases.optJSONObject(hash);if(a==null)continue;String binding=a.optInt("cid")+":"+a.optString("server_hash");if(active.contains(binding))bindings.put(hash,binding);}
        for(int i=0;i<scores.length();i++){JSONObject s=scores.getJSONObject(i);records.add(s.optString("id")+"|"+s.optString("server_hash"));}
        JSONObject previous=saved.optJSONObject("files");if(previous==null)return;
        if(saved.optInt("schema")==2&&server.equals(saved.optString("server"))){Iterator<String> it=previous.keys();while(it.hasNext()){String p=it.next();if(previous.optJSONObject(p)!=null)files.put(p,previous.getJSONObject(p));}}
        else if(saved.optInt("schema")==1&&revision.equals(saved.optString("revision"))){
            // Reuse old stamps only while their original global dependencies are valid.
            ScanCache old=new ScanCache(saved,revision,scores);Iterator<String> it=previous.keys();while(it.hasNext()){String p=it.next(),stamp=previous.optString(p);if(old.unchanged(p,stamp))files.put(p,new JSONObject().put("stamp",stamp).put("legacy",revision));}
        }
    }
    public boolean unchanged(String path,String stamp){
        JSONObject row=files.optJSONObject(path);if(stamp.isEmpty()||row==null||!stamp.equals(row.optString("stamp")))return false;
        if(row.has("legacy"))return revision.equals(row.optString("legacy"));
        if(!Objects.equals(row.optString("binding"),bindings.getOrDefault(row.optString("hash"),"")))return false;
        String record=row.optString("record");return record.isEmpty()||records.contains(record);
    }
    public void checked(String path,String stamp,String hash,String record)throws Exception {
        if(stamp.isEmpty())return;files.put(path,new JSONObject().put("stamp",stamp).put("hash",hash).put("binding",bindings.getOrDefault(hash,"")).put("record",record));
        if(!record.isEmpty())records.add(record);
    }
    public JSONObject snapshot(List<String> paths)throws Exception {
        JSONObject present=new JSONObject();for(String p:paths)if(files.has(p))present.put(p,files.get(p));
        return new JSONObject().put("schema",2).put("server",server).put("files",present);
    }
}
