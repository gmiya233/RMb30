package cn.gmiya.malodylink;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** Versioned, catalogue-filtered score data. QQ identifies an archive, not an authenticated user. */
public final class CloudRecords {
    public static final int MAX_RECORDS=20000,MAX_BATCH=256,MAX_BYTES=1048576;
    private static final Pattern ID=Pattern.compile("([1-9][0-9]*):([0-9]+):[a-f0-9]{32}");
    public static int judge(JSONObject score){
        JSONObject raw=score.optJSONObject("raw_score_fields");if(raw==null||raw.length()==0)return 0;
        // Protobuf omits the zero-valued A enum. Preserve raw fields without rewriting them.
        if(!raw.has("4"))return RatingRules.replayJudge(0);
        Object value=raw.opt("4");return value instanceof Number?RatingRules.replayJudge(((Number)value).doubleValue()):0;
    }
    public static String qq(String value){String s=value==null?"":value.trim();if(!s.matches("[1-9][0-9]{4,11}"))throw new IllegalArgumentException("QQ号请填写5～12位数字，不能以0开头");return s;}
    public static String sha256(byte[] bytes)throws Exception {StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes)){s.append(Character.forDigit((b>>>4)&15,16));s.append(Character.forDigit(b&15,16));}return s.toString();}
    public static byte[] read(InputStream input,int max)throws IOException {try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("云端数据超过读取上限");out.write(b,0,n);}return out.toByteArray();}}
    public static final class Catalogue {
        private final Map<Integer,JSONObject> rows=new HashMap<>();
        public Catalogue(JSONArray charts)throws Exception {for(int i=0;i<charts.length();i++){JSONObject row=charts.getJSONObject(i);rows.put(row.getInt("cid"),row);}}
        public JSONObject clean(JSONObject input){try{
            String id=input.getString("id"),hash=input.getString("server_hash"),md5=input.getString("chart_md5");Matcher m=ID.matcher(id);if(!m.matches()||!md5.matches("[a-f0-9]{32}"))return null;
            int cid=input.getInt("cid");long played=input.getLong("played_at"),score=input.getLong("score_candidate");double accuracy=input.getDouble("accuracy_candidate");
            if(cid!=Integer.parseInt(m.group(1))||played!=Long.parseLong(m.group(2))||played<0||score<0||!Double.isFinite(accuracy)||accuracy<0||accuracy>100)return null;
            JSONObject chart=rows.get(cid);if(chart==null||!hash.equals(chart.optString("server_hash")))return null;
            JSONObject raw=input.optJSONObject("raw_score_fields"),fields=new JSONObject();
            if(raw!=null){if(raw.length()>64)return null;Iterator<String> keys=raw.keys();while(keys.hasNext()){String k=keys.next();Object v=raw.get(k);if(k.matches("[1-9][0-9]{0,2}")&&v instanceof Number&&Double.isFinite(((Number)v).doubleValue()))fields.put(k,v);}}
            String player=input.optString("player","");if(player.length()>128)player=player.substring(0,128);
            return new JSONObject().put("id",id).put("cid",cid).put("server_hash",hash).put("chart_md5",md5)
                .put("title",chart.optString("title")).put("version",chart.optString("version")).put("player",player)
                .put("played_at",played).put("score_candidate",score).put("accuracy_candidate",accuracy).put("raw_score_fields",fields)
                .put("verification","catalog_identity").put("field_mapping_verified",false).put("uploaded",false);
        }catch(Exception invalid){return null;}}
    }
    public static final class Merged {public JSONArray scores;public int added,skipped,conflicts;}
    public static Merged merge(JSONArray local,JSONArray remote,Catalogue catalogue)throws Exception {
        Merged result=new Merged();TreeMap<String,JSONObject> rows=new TreeMap<>();Set<String> localIds=new HashSet<>(),conflicts=new HashSet<>();
        for(int source=0;source<2;source++){JSONArray input=source==0?local:remote;for(int i=0;i<input.length();i++){
            JSONObject record=catalogue.clean(input.optJSONObject(i));if(record==null){result.skipped++;continue;}String id=record.getString("id");JSONObject existing=rows.get(id);
            if(source==0)localIds.add(id);
            if(existing==null){if(rows.size()>=MAX_RECORDS)throw new IOException("存档超过20000条记录，本次未覆盖本地数据");rows.put(id,record);}
            else if(!ChartMatcher.canonical(existing).equals(ChartMatcher.canonical(record))){conflicts.add(id);if(!localIds.contains(id)&&ChartMatcher.canonical(record).compareTo(ChartMatcher.canonical(existing))<0)rows.put(id,record);}
        }}
        result.scores=new JSONArray();for(Map.Entry<String,JSONObject> row:rows.entrySet()){result.scores.put(row.getValue());if(!localIds.contains(row.getKey()))result.added++;}result.conflicts=conflicts.size();return result;
    }
    public static String fingerprint(JSONObject row)throws Exception {return sha256(ChartMatcher.canonical(row).getBytes(StandardCharsets.UTF_8));}
    public static byte[] pack(String qq,String server,JSONArray rows)throws Exception {
        if(rows.length()>MAX_BATCH)throw new IOException("云端数据分片过大");byte[] data=ChartMatcher.canonical(new JSONObject().put("schema",1).put("qq",qq(qq)).put("server",server).put("scores",rows)).getBytes(StandardCharsets.UTF_8);
        if(data.length>MAX_BYTES)throw new IOException("云端数据分片超过1MiB");return data;
    }
    public static JSONArray unpack(byte[] bytes,String qq,String server)throws Exception {
        if(bytes.length>MAX_BYTES)throw new IOException("云端数据分片超过1MiB");JSONObject doc=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(doc.optInt("schema")!=1||!qq(qq).equals(doc.optString("qq")))throw new IOException("云端数据格式或QQ存档编号不匹配");
        if(!server.equals(doc.optString("server")))return new JSONArray();JSONArray rows=doc.getJSONArray("scores");if(rows.length()>MAX_BATCH)throw new IOException("云端分片记录过多");return rows;
    }
}
