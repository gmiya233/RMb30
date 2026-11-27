package cn.gmiya.malodylink;
import android.content.Context;import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;import org.json.*;
final class GameplaySource {
 static final class Loaded {GameplayChart chart;File music;String source,warning="";}
 static String child(String dir,String name)throws IOException{if(name.isEmpty()||name.startsWith("/")||name.contains("\\")||name.contains(":")||name.contains("..")||name.chars().anyMatch(v->v<32))throw new IOException("无效资源路径");return dir+"/"+name;}
 static File music(Context c,byte[] bytes,String name)throws Exception{if(bytes.length==0||bytes.length>33554432)throw new IOException("音乐文件大小无效");String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);if(!Arrays.asList("ogg","mp3","wav","m4a").contains(ext))throw new IOException("音频格式不支持");File f=File.createTempFile("autoplay-","."+ext,c.getCacheDir());try(FileOutputStream out=new FileOutputStream(f)){out.write(bytes);}return f;}
 static Loaded load(Context c,Engine engine,JSONObject row,Engine.Progress progress)throws Exception {
 Loaded result=new Loaded();String reason="本机未连接";
 try {progress.update("正在查找本机谱面…");InternalAdb adb=InternalAdb.get(c);LocalScoreSync.connect(c,adb,0);Engine.Reader reader=adb.reader();String selected=null;byte[] mc=null;
 for(String path:reader.list("chart")){if(!path.endsWith("/"+row.getString("mc_name")))continue;byte[] data=reader.file(path);if(ChartMatcher.catalogMatches(data,row.getString("mc_name"),row)){if(selected!=null)throw new IOException("多个本机谱面匹配，无法确定版本");selected=path;mc=data;}}
 if(selected==null)throw new IOException("本机未找到此谱面");GameplayChart chart=GameplayChart.mc(mc,ChartKeys.count(row));String dir=selected.substring(0,selected.lastIndexOf('/'));
 for(String name:new String[]{row.getString("mc_name").replaceFirst("(?i)\\.mc$",".imd"),row.optString("title")+".imd"}){byte[] original;try{original=reader.file(child(dir,name));}catch(Exception absent){continue;}GameplayChart parsed=GameplayChart.imd(original,chart.keys);parsed.audioName=chart.audioName;chart=parsed;break;}
 result.chart=chart;result.source="本机 · "+chart.format;progress.update("正在读取本机音乐…");try{result.music=music(c,reader.file(child(dir,chart.audioName)),chart.audioName);}catch(Exception missing){result.warning="本机音乐不可用，静音预览";}return result;
 }catch(Exception e){reason=e.getMessage();}
 progress.update("正在读取服务器谱面…");try{
 if(!engine.server.equals(RankConnection.config(c).getString("server")))throw new IOException("预览服务器不匹配");OpenListScoreRemote remote=RankConnection.open(c);JSONObject d=new JSONObject(new String(remote.getPath("/_chart_previews/v1/playback/"+row.optInt("cid")+".json",16777216),StandardCharsets.UTF_8));
 if(d.optInt("schema")!=1||!engine.server.equals(d.optString("server"))||d.optInt("cid")!=row.optInt("cid")||!row.optString("server_hash").equals(d.optString("server_hash")))throw new IOException("谱面版本已变化，请更新谱库");
 result.chart=GameplayChart.normalized(d.getJSONObject("chart"));result.source="服务器 · "+result.chart.format;result.warning="音乐暂不可用，静音预览";JSONObject audio=d.optJSONObject("audio");
 if(audio!=null){String file=audio.getString("file"),hash=audio.getString("sha256");if(!file.matches("[a-f0-9]{64}\\.(ogg|mp3|wav|m4a)")||!hash.matches("[a-f0-9]{64}"))throw new IOException("音乐资源无效");try{byte[] bytes=remote.getPath("/_chart_previews/v1/playback/"+file,33554432);if(bytes.length!=audio.getInt("bytes")||!CloudRecords.sha256(bytes).equals(hash))throw new IOException("音乐校验失败");result.music=music(c,bytes,file);result.warning="";}catch(Exception unavailable){}}
 return result;
 }catch(Exception e){throw new IOException("本机："+reason+"；服务器："+e.getMessage()+"。请检查本机读取连接或稍后重试。");}
 }
}
