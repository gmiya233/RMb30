package cn.gmiya.malodylink;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Only an unlocked title key is shared; nicknames, avatars and scores stay separate. */
public final class PlayerTitle {
    public static final String FILE="player-title.json";
    public static String automatic(double rating){return !Double.isFinite(rating)||rating<10?"小师妹":rating<11?"萌新":rating<12?"高手":rating<13?"大师":"龙币";}
    public static byte[] pack(String qq,String server,String key)throws Exception {
        if(!key.isEmpty()&&!key.matches("(?:rank:(?:0|10|11|12|13)|(?:sid|cid):[1-9][0-9]{0,9})"))throw new IllegalArgumentException("称号编号无效");
        return new JSONObject().put("schema",1).put("qq",CloudRecords.qq(qq)).put("server",server).put("selected",key).toString().getBytes(StandardCharsets.UTF_8);
    }
    public static String resolve(JSONObject doc,String qq,String server,JSONArray records,JSONArray charts,double rating){
        String fallback=automatic(rating);
        if(doc==null||doc.optInt("schema")!=1||!qq.equals(doc.optString("qq"))||!server.equals(doc.optString("server")))return fallback;
        String key=doc.optString("selected");
        if(key.equals("rank:0"))return "小师妹";
        for(int rank=10;rank<=13;rank++)if(key.equals("rank:"+rank)&&rating>=rank)return automatic(rank);
        if(!key.matches("(?:sid|cid):[1-9][0-9]{0,9}"))return fallback;
        Map<String,JSONObject> active=new HashMap<>();for(int i=0;i<charts.length();i++){JSONObject c=charts.optJSONObject(i);if(c!=null)active.put(c.optInt("cid")+":"+c.optString("server_hash"),c);}
        for(int i=0;i<records.length();i++){JSONObject s=records.optJSONObject(i);if(s==null||s.optBoolean("demo")||s.optBoolean("manual"))continue;double a=s.optDouble("accuracy_candidate");if(!Double.isFinite(a)||a<=99.8||a>100)continue;
            JSONObject c=active.get(s.optInt("cid")+":"+s.optString("server_hash"));if(c==null)continue;String id=c.optInt("sid")>0?"sid:"+c.optInt("sid"):"cid:"+c.optInt("cid");String title=c.optString("title").trim();if(key.equals(id)&&!title.isEmpty()&&title.length()<=128)return title;
        }return fallback;
    }
}
