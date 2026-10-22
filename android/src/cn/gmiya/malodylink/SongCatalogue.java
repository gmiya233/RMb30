package cn.gmiya.malodylink;

import org.json.*;
import java.text.Normalizer;
import java.util.*;

/** Searchable, current-catalogue songs with separate difficulty charts. */
public final class SongCatalogue {
    public static final int ALL=0,TITLE=1,ARTIST=2,CREATOR=3,LEVEL=4,KEYS=5;
    public static final class Song {public int sid;public String title="",artist="",bpm="",cover="",background="";public final List<JSONObject> charts=new ArrayList<>();}
    public final List<Song> songs=new ArrayList<>();private final Map<Integer,Song> byChart=new HashMap<>();
    public SongCatalogue(JSONArray charts){Map<String,Song> groups=new LinkedHashMap<>();for(int i=0;i<charts.length();i++){JSONObject c=charts.optJSONObject(i);if(c==null)continue;int cid=c.optInt("cid"),sid=c.optInt("sid");String key=sid>0?"sid:"+sid:"cid:"+cid;Song s=groups.get(key);if(s==null){s=new Song();s.sid=sid>0?sid:-cid;s.title=c.optString("title");groups.put(key,s);}if(s.artist.isEmpty())s.artist=nonempty(c,"artist");if(s.bpm.isEmpty())s.bpm=nonempty(c,"bpm");if(s.cover.isEmpty())s.cover=nonempty(c,"cover");if(s.background.isEmpty())s.background=nonempty(c,"background");s.charts.add(c);byChart.put(cid,s);}songs.addAll(groups.values());for(Song s:songs)s.charts.sort((a,b)->{double x=ChartDetails.level(rawLevel(a),a.optString("version")),y=ChartDetails.level(rawLevel(b),b.optString("version"));int n=Double.compare(Double.isFinite(x)?x:Double.MAX_VALUE,Double.isFinite(y)?y:Double.MAX_VALUE);return n!=0?n:Integer.compare(a.optInt("cid"),b.optInt("cid"));});songs.sort((a,b)->a.title.compareToIgnoreCase(b.title));}
    private static String nonempty(JSONObject o,String key){String s=o.optString(key,"").trim();return s.equalsIgnoreCase("null")?"":s;}
    public static double rawLevel(JSONObject c){double n=c.optDouble("level");if(Double.isFinite(n)&&n>0)return n;java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)(?:lv\\.?|level)\\s*(\\d+(?:\\.\\d+)?)").matcher(c.optString("version"));return m.find()?Double.parseDouble(m.group(1)):Double.NaN;}
    public static String levelLabel(JSONObject c){double value=ChartDetails.level(rawLevel(c),c.optString("version"));return Double.isFinite(value)?"Lv."+(value==Math.rint(value)?Integer.toString((int)value):Double.toString(value)):"定级未提供";}
    public Song forChart(int cid){return byChart.get(cid);}
    public static String highestLevelLabel(List<JSONObject> charts){
        JSONObject highest=null;double maximum=0;
        for(JSONObject chart:charts){double level=ChartDetails.level(rawLevel(chart),chart.optString("version"));if(Double.isFinite(level)&&level>maximum){maximum=level;highest=chart;}}
        return highest==null?"未定级":levelLabel(highest);
    }
    public static int keyQuery(String query){
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("^([1-9][0-9]?)(?:\\s*(?:k|键))?$").matcher(normalize(query));
        if(!match.matches())return 0;int count=Integer.parseInt(match.group(1));return count<=64?count:0;
    }
    public List<Song> search(String query){String normalized=normalize(query);List<Song> result=new ArrayList<>();String[] words=normalized.isEmpty()?new String[0]:normalized.split("\\s+");for(Song s:songs){StringBuilder haystack=new StringBuilder(s.title+" "+s.artist+" "+s.sid);for(JSONObject c:s.charts)haystack.append(' ').append(c.optString("creator")).append(' ').append(c.optString("version")).append(' ').append(c.optInt("cid"));String text=normalize(haystack.toString());boolean match=true;for(String word:words)if(!text.contains(word)){match=false;break;}if(match)result.add(s);}return result;}
    public List<Song> search(String query,int mode){
        if(mode==ALL)return search(query);
        String normalized=normalize(query);if(normalized.isEmpty())return new ArrayList<>(songs);
        List<Song> result=new ArrayList<>();
        if(mode==KEYS){
            int target=keyQuery(normalized);if(target==0)return result;
            for(Song song:songs)for(JSONObject chart:song.charts)if(ChartKeys.count(chart)==target){result.add(song);break;}
            return result;
        }
        if(mode==LEVEL){
            double target;try{target=Double.parseDouble(normalized.replaceFirst("^lv\\.?\\s*",""));}catch(NumberFormatException invalid){return result;}
            if(!Double.isFinite(target)||target<=0)return result;
            for(Song song:songs)for(JSONObject chart:song.charts){
                double value=ChartDetails.level(rawLevel(chart),chart.optString("version"));
                if(Double.compare(value,target)==0){result.add(song);break;}
            }return result;
        }
        for(Song song:songs){StringBuilder value=new StringBuilder(mode==TITLE?song.title:mode==ARTIST?song.artist:"");
            if(mode==CREATOR)for(JSONObject chart:song.charts)value.append(' ').append(chart.optString("creator"));
            String haystack=normalize(value.toString());boolean matched=true;for(String word:normalized.split("\\s+"))if(!haystack.contains(word)){matched=false;break;}
            if(matched)result.add(song);
        }return result;
    }
    public List<Song> search(String query,int mode,boolean hideUngraded){
        List<Song> result=search(query,mode);
        if(hideUngraded)result.removeIf(song->matchingCharts(song,query,mode,true).isEmpty());
        return result;
    }
    public static List<JSONObject> matchingCharts(Song song,String query,int mode,boolean hideUngraded){
        String normalized=normalize(query);int keys=0;double level=Double.NaN;
        if(!normalized.isEmpty()){
            if(mode==KEYS){keys=keyQuery(normalized);if(keys==0)return new ArrayList<>();}
            if(mode==LEVEL){try{level=Double.parseDouble(normalized.replaceFirst("^lv\\.?\\s*",""));}catch(NumberFormatException invalid){return new ArrayList<>();}if(!Double.isFinite(level)||level<=0)return new ArrayList<>();}
        }
        List<JSONObject> result=new ArrayList<>();
        for(JSONObject chart:song.charts){
            double displayed=ChartDetails.level(rawLevel(chart),chart.optString("version"));
            if(hideUngraded&&(!Double.isFinite(displayed)||displayed<=0))continue;
            if(keys>0&&ChartKeys.count(chart)!=keys)continue;
            if(Double.isFinite(level)&&Double.compare(displayed,level)!=0)continue;
            result.add(chart);
        }return result;
    }
    private static String normalize(String text){return Normalizer.normalize(text==null?"":text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();}
}
