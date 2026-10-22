package cn.gmiya.malodylink;

import org.json.*;
import java.text.Normalizer;
import java.util.*;

/** Searchable, current-catalogue songs with separate difficulty charts. */
public final class SongCatalogue {
    public static final class Song {public int sid;public String title="",artist="",bpm="",cover="",background="";public final List<JSONObject> charts=new ArrayList<>();}
    public final List<Song> songs=new ArrayList<>();private final Map<Integer,Song> byChart=new HashMap<>();
    public SongCatalogue(JSONArray charts){Map<String,Song> groups=new LinkedHashMap<>();for(int i=0;i<charts.length();i++){JSONObject c=charts.optJSONObject(i);if(c==null)continue;int cid=c.optInt("cid"),sid=c.optInt("sid");String key=sid>0?"sid:"+sid:"cid:"+cid;Song s=groups.get(key);if(s==null){s=new Song();s.sid=sid>0?sid:-cid;s.title=c.optString("title");groups.put(key,s);}if(s.artist.isEmpty())s.artist=nonempty(c,"artist");if(s.bpm.isEmpty())s.bpm=nonempty(c,"bpm");if(s.cover.isEmpty())s.cover=nonempty(c,"cover");if(s.background.isEmpty())s.background=nonempty(c,"background");s.charts.add(c);byChart.put(cid,s);}songs.addAll(groups.values());for(Song s:songs)s.charts.sort((a,b)->{double x=ChartDetails.level(rawLevel(a),a.optString("version")),y=ChartDetails.level(rawLevel(b),b.optString("version"));int n=Double.compare(Double.isFinite(x)?x:Double.MAX_VALUE,Double.isFinite(y)?y:Double.MAX_VALUE);return n!=0?n:Integer.compare(a.optInt("cid"),b.optInt("cid"));});songs.sort((a,b)->a.title.compareToIgnoreCase(b.title));}
    private static String nonempty(JSONObject o,String key){String s=o.optString(key,"").trim();return s.equalsIgnoreCase("null")?"":s;}
    public static double rawLevel(JSONObject c){double n=c.optDouble("level");if(Double.isFinite(n)&&n>0)return n;java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)(?:lv\\.?|level)\\s*(\\d+(?:\\.\\d+)?)").matcher(c.optString("version"));return m.find()?Double.parseDouble(m.group(1)):Double.NaN;}
    public static String levelLabel(JSONObject c){double value=ChartDetails.level(rawLevel(c),c.optString("version"));return Double.isFinite(value)?"Lv."+(value==Math.rint(value)?Integer.toString((int)value):Double.toString(value)):"定级未提供";}
    public Song forChart(int cid){return byChart.get(cid);}
    public List<Song> search(String query){String normalized=normalize(query);List<Song> result=new ArrayList<>();String[] words=normalized.isEmpty()?new String[0]:normalized.split("\\s+");for(Song s:songs){StringBuilder haystack=new StringBuilder(s.title+" "+s.artist+" "+s.sid);for(JSONObject c:s.charts)haystack.append(' ').append(c.optString("creator")).append(' ').append(c.optString("version")).append(' ').append(c.optInt("cid"));String text=normalize(haystack.toString());boolean match=true;for(String word:words)if(!text.contains(word)){match=false;break;}if(match)result.add(s);}return result;}
    private static String normalize(String text){return Normalizer.normalize(text==null?"":text,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).trim();}
}
