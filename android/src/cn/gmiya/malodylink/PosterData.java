package cn.gmiya.malodylink;

import android.content.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public final class PosterData {
    public static class Entry {
        public int cid,sid,judge;public double level,accuracy,converted;public long score;
        public String difficultyVersion;public boolean levelOverride;
        public double displayLevel(){return levelOverride?RatingRules.normalizedLevel(level):ChartDetails.level(level,difficultyVersion);}
        public String title,version,player,section;public boolean manual,demo;
        public String identity(){return sid>0?"sid:"+sid:"cid:"+cid;}
    }
    public static class Board {
        public List<Entry> old=new ArrayList<>(),fresh=new ArrayList<>();
        public String player="PLAYER",title="";public boolean demo;public int allCount;
        public int count(){return old.size()+fresh.size();}
        public double oldRating(){return average(old,20);}
        public double newRating(){return average(fresh,10);}
        public double rating(){return (oldRating()*20+newRating()*10)/30;}
        private static double average(List<Entry> entries,int slots){double sum=0;for(Entry e:entries)if(Double.isFinite(e.converted))sum+=e.converted;return sum/slots;}
    }
    public static final Comparator<Entry> ORDER=(a,b)->{
        boolean af=Double.isFinite(a.converted),bf=Double.isFinite(b.converted);
        if(af!=bf)return af?-1:1;
        int r=af?Double.compare(b.converted,a.converted):0;
        if(r==0)r=Double.compare(b.accuracy,a.accuracy);
        if(r==0)r=Long.compare(b.score,a.score);return r==0?Integer.compare(a.cid,b.cid):r;
    };
    public static Board group(List<Entry> entries){
        Map<String,Entry> best=new HashMap<>();
        // Deduplicate a song across charts; N status wins if any current chart is promoted.
        Set<String> freshSongs=new HashSet<>();for(Entry e:entries)if("N".equals(e.section))freshSongs.add(e.identity());
        for(Entry e:entries){String key=e.identity();Entry prev=best.get(key);if(prev==null||ORDER.compare(e,prev)<0)best.put(key,e);}
        Board b=new Board();b.allCount=best.size();for(Entry e:best.values()){e.section=freshSongs.contains(e.identity())?"N":"O";if("N".equals(e.section))b.fresh.add(e);else b.old.add(e);}
        b.old.sort(ORDER);b.fresh.sort(ORDER);if(b.old.size()>20)b.old=new ArrayList<>(b.old.subList(0,20));if(b.fresh.size()>10)b.fresh=new ArrayList<>(b.fresh.subList(0,10));return b;
    }
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
        Board board=group(entries);board.demo=demo;if(!entries.isEmpty())board.player=entries.get(0).player;if(appearance){board.player=LocalPlayerProfile.nickname(context,board.player);board.title=LocalPlayerProfile.title(context,engine);}return board;
    }
    private static Entry from(JSONObject c){Entry e=new Entry();e.cid=c.optInt("cid");e.sid=c.optInt("sid",e.cid);e.title=c.optString("title");e.version=c.optString("version");e.difficultyVersion=e.version;e.level=level(c);
        e.section=(c.optBoolean("promote",false)||c.optInt("promote",0)==1||c.optString("path").startsWith("promote/"))?"N":"O";return e;}
}
