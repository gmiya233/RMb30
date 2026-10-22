package cn.gmiya.malodylink;

import android.content.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public final class PosterData extends RatingBoard {
    private static double level(JSONObject c){double v=c.optDouble("level",0);if(v>0)return v;Matcher m=Pattern.compile("(?i)(?:lv\\.?|level)\\s*(\\d+(?:\\.\\d+)?)").matcher(c.optString("version"));return m.find()?Double.parseDouble(m.group(1)):Double.NaN;}
    public static Board load(Context context,Engine engine,boolean demo,RatingRules rules,int judgeOverride)throws Exception {
        return load(context,engine,demo,rules,judgeOverride,true,true);
    }
    public static Board loadRaw(Context context,Engine engine,RatingRules rules)throws Exception{return load(context,engine,false,rules,0,false,false);}
    private static Board load(Context context,Engine engine,boolean demo,RatingRules rules,int judgeOverride,boolean preview,boolean appearance)throws Exception {
        JSONObject poster=new JSONObject(new String(Engine.read(context.getAssets().open("poster-catalog.json"),8000000),StandardCharsets.UTF_8));
        Map<Integer,JSONObject> all=new HashMap<>();JSONArray enriched=poster.getJSONArray("charts");for(int i=0;i<enriched.length();i++){JSONObject c=enriched.getJSONObject(i);all.put(c.getInt("cid"),c);}
        for(int i=0;i<engine.charts().length();i++){JSONObject c=engine.charts().getJSONObject(i);JSONObject base=all.get(c.getInt("cid"));if(base!=null){JSONObject merged=new JSONObject(base.toString());Iterator<String> keys=c.keys();while(keys.hasNext()){String key=keys.next();merged.put(key,c.get(key));}all.put(c.getInt("cid"),merged);}else all.put(c.getInt("cid"),c);}
        SharedPreferences prefs=context.getSharedPreferences("poster",0);List<Entry> entries=new ArrayList<>();
        if(demo){JSONArray ids=poster.getJSONArray("demo_cids");for(int i=0;i<ids.length();i++){JSONObject c=all.get(ids.getInt(i));if(c==null||!engine.active(c.optInt("cid"),c.optString("server_hash")))continue;
            Entry e=from(c);e.demo=true;e.player="GM1YA / 布局示例";e.judge=judgeOverride>0?judgeOverride:3+i%3;e.accuracy=new double[]{99.85,99.8,99.75,99.65,99.5}[i%5];e.score=1000000-i*723L;
            e.converted=rules.rating(e.level,e.difficultyVersion,e.accuracy,e.judge);entries.add(e);}
        }else{
            for(int i=0;i<engine.scores.length();i++){JSONObject s=engine.scores.getJSONObject(i);if(!engine.active(s.optInt("cid"),s.optString("server_hash")))continue;JSONObject c=all.get(s.getInt("cid"));if(c==null)continue;
                Entry e=from(c);e.player=s.optString("player","PLAYER");e.score=s.optLong("score_candidate");e.accuracy=s.optDouble("accuracy_candidate");
                JSONObject raw=s.optJSONObject("raw_score_fields");e.judge=raw!=null?raw.optInt("4",0):0;if(e.judge<1||e.judge>5)e.judge=0;
                String saved=preview?prefs.getString("entry_"+e.cid,""):"";if(!saved.isEmpty()){JSONObject override=new JSONObject(saved);e.level=override.optDouble("level",e.level);e.accuracy=override.optDouble("accuracy",e.accuracy);e.judge=override.optInt("judge",e.judge);e.manual=true;e.levelOverride=override.has("level");}
                if(judgeOverride>0){e.judge=judgeOverride;e.manual=true;}e.converted=rules.rating(e.level,e.difficultyVersion,e.accuracy,e.judge);if(e.levelOverride)e.converted=rules.converted(e.level,e.accuracy,e.judge);entries.add(e);
            }
        }
        Set<String> promoted=RankingData.freshSongs(engine.charts());for(Entry e:entries)if(promoted.contains(e.identity()))e.section="N";Board board=group(entries);board.demo=demo;if(!entries.isEmpty())board.player=entries.get(0).player;if(appearance){board.player=LocalPlayerProfile.nickname(context,board.player);board.title=LocalPlayerProfile.title(context,engine);}return board;
    }
    private static Entry from(JSONObject c){Entry e=new Entry();e.cid=c.optInt("cid");e.sid=c.optInt("sid",e.cid);e.title=c.optString("title");e.version=c.optString("version");e.difficultyVersion=e.version;e.level=level(c);e.keys=ChartKeys.count(c);
        e.section=(c.optBoolean("promote",false)||c.optInt("promote",0)==1||c.optString("path").startsWith("promote/"))?"N":"O";return e;}
}
