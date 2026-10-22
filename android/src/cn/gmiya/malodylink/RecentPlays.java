package cn.gmiya.malodylink;

import org.json.*;
import java.util.*;

/** Recent plays are chronological history, not each song's personal best. */
public final class RecentPlays {
    public static final int LIMIT=50;
    public static final class Play {public JSONObject record,chart;public double level,rating;public int judge;}
    public static List<Play> load(JSONArray scores,JSONArray charts){
        Map<Integer,JSONObject> catalogue=new HashMap<>();for(int i=0;i<charts.length();i++){JSONObject c=charts.optJSONObject(i);if(c!=null)catalogue.put(c.optInt("cid"),c);}
        List<Play> rows=new ArrayList<>();RatingRules rules=new RatingRules();
        for(int i=0;i<scores.length();i++){
            JSONObject s=scores.optJSONObject(i);if(s==null)continue;JSONObject c=catalogue.get(s.optInt("cid"));
            if(c==null||!c.optString("server_hash").equals(s.optString("server_hash")))continue;
            Play p=new Play();p.record=s;p.chart=c;JSONObject fields=s.optJSONObject("raw_score_fields");p.judge=fields==null?0:fields.optInt("4");
            p.level=ChartDetails.level(SongCatalogue.rawLevel(c),c.optString("version"));
            try{p.rating=rules.rating(SongCatalogue.rawLevel(c),c.optString("version"),s.optDouble("accuracy_candidate"),p.judge);}catch(IllegalArgumentException invalid){p.rating=Double.NaN;}
            rows.add(p);
        }
        rows.sort((a,b)->{int order=Long.compare(b.record.optLong("played_at"),a.record.optLong("played_at"));return order!=0?order:a.record.optString("id").compareTo(b.record.optString("id"));});
        return new ArrayList<>(rows.subList(0,Math.min(LIMIT,rows.size())));
    }
}
