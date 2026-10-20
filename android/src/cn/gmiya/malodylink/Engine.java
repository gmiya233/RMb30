package cn.gmiya.malodylink;

import android.content.*;
import android.database.*;
import android.database.sqlite.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

public final class Engine {
    public interface Progress {void update(String text);}
    private final Context context;
    public JSONObject catalog,aliases;
    public JSONArray scores;
    // Shared only inside the current app process, never persisted or sent through intents.
    private static final Map<String,String> sessionTokens=Collections.synchronizedMap(new HashMap<>());
    private String token="";
    public String server="http://47.111.184.207:5244";
    public Engine(Context ctx)throws Exception {
        context=ctx;server=ctx.getSharedPreferences("settings",0).getString("server",server);
        String session=sessionTokens.get(server);if(session!=null)token=session;
        catalog=load("catalog.json",new JSONObject(new String(read(ctx.getAssets().open("catalog.json"),8000000),StandardCharsets.UTF_8)));
        aliases=load("aliases.json",new JSONObject());scores=loadArray("scores.json");
        // Keep only results still present in this exact catalogue revision.
        JSONArray filtered=new JSONArray();for(int i=0;i<scores.length();i++){JSONObject s=scores.getJSONObject(i);if(active(s.optInt("cid"),s.optString("server_hash")))filtered.put(s);}scores=filtered;
    }
    public static byte[] read(InputStream stream,int maximum)throws IOException {
        try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1){if(out.size()+n>maximum)throw new IOException("文件过大，已停止读取");out.write(b,0,n);}return out.toByteArray();}
    }
    public JSONObject load(String name,JSONObject fallback)throws Exception {File f=new File(context.getFilesDir(),name);return f.exists()?new JSONObject(new String(read(new FileInputStream(f),16000000),StandardCharsets.UTF_8)):fallback;}
    private JSONArray loadArray(String name)throws Exception {File f=new File(context.getFilesDir(),name);return f.exists()?new JSONArray(new String(read(new FileInputStream(f),16000000),StandardCharsets.UTF_8)):new JSONArray();}
    private void save(String name,Object value)throws Exception {File tmp=new File(context.getFilesDir(),name+".tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(value.toString().getBytes(StandardCharsets.UTF_8));}if(!tmp.renameTo(new File(context.getFilesDir(),name)))throw new IOException("保存失败");}
    public JSONArray charts(){return catalog.optJSONArray("charts");}
    public boolean active(int cid,String hash){JSONArray a=charts();for(int i=0;i<a.length();i++){JSONObject c=a.optJSONObject(i);if(c.optInt("cid")==cid&&c.optString("server_hash").equals(hash))return true;}return false;}
    private JSONObject request(String path,JSONObject data)throws Exception {return new JSONObject(new String(http(server+path,"POST",data.toString().getBytes(StandardCharsets.UTF_8),true,8000000),StandardCharsets.UTF_8));}
    private byte[] http(String address,String method,byte[] body,boolean auth,int limit)throws Exception {
        URL url=new URL(address);if(!url.getProtocol().equals("http")&&!url.getProtocol().equals("https"))throw new IOException("仅支持 HTTP 或 HTTPS");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(25000);c.setRequestMethod(method);
        if(auth&&!token.isEmpty())c.setRequestProperty("Authorization",token);
        try{if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream out=c.getOutputStream()){out.write(body);}}
            int code=c.getResponseCode();if(code>=300&&code<400){String location=c.getHeaderField("Location");c.disconnect();if(!method.equals("GET"))throw new IOException("服务地址发生重定向，请检查设置");return http(new URL(url,location).toString(),"GET",null,false,limit);}
            if(code!=200)throw new IOException("HTTP "+code);return read(c.getInputStream(),limit);
        }finally{c.disconnect();}
    }
    public byte[] serverFile(String path,int limit)throws Exception {
        JSONObject r=request("/api/fs/get",new JSONObject().put("path",path).put("password",""));if(r.optInt("code")!=200)throw new IOException("无法读取服务器文件："+r.optString("message"));
        String raw=r.getJSONObject("data").getString("raw_url");URL a=new URL(server),b=new URL(raw);
        boolean same=a.getProtocol().equals(b.getProtocol())&&a.getHost().equals(b.getHost())&&a.getPort()==b.getPort();
        return http(raw,"GET",null,same,limit);
    }
    public void connect(String address,String user,String password,Progress p)throws Exception {
        address=address.trim().replaceAll("/+$","");URL u=new URL(address);if(!u.getProtocol().equals("http")&&!u.getProtocol().equals("https"))throw new IOException("请输入有效服务器地址");
        String oldServer=server;server=address;token="";
        JSONObject r=request("/api/auth/login",new JSONObject().put("username",user).put("password",password));if(r.optInt("code")!=200){server=oldServer;throw new IOException("登录失败："+r.optString("message"));}
        token=r.getJSONObject("data").getString("token");sessionTokens.put(server,token);p.update("读取服务器当前谱面清单…");
        byte[] bytes=serverFile("/data/malody.db",16000000);installCatalogue(bytes,address,oldServer);context.getSharedPreferences("settings",0).edit().putBoolean("default_reader",false).apply();
    }
    public void installDefaultCatalogue(byte[] bytes,String address)throws Exception {installCatalogue(bytes,address,server);context.getSharedPreferences("settings",0).edit().putBoolean("default_reader",true).apply();}
    private void installCatalogue(byte[] bytes,String address,String oldServer)throws Exception {
        if(bytes.length>16000000||bytes.length<16||!new String(bytes,0,16,StandardCharsets.US_ASCII).equals("SQLite format 3\u0000"))throw new IOException("下载内容不是有效谱库数据库");
        File temp=new File(context.getCacheDir(),"catalog-download.db");
        try{
            try(FileOutputStream out=new FileOutputStream(temp)){out.write(bytes);}
            JSONArray rows=new JSONArray();SQLiteDatabase db=SQLiteDatabase.openDatabase(temp.getPath(),null,SQLiteDatabase.OPEN_READONLY);
            try(Cursor cursor=db.rawQuery("SELECT c.cid,c.hash,c.path,c.mc_name,c.version,c.mode,s.title,s.artist,s.sid,c.level,s.promote,s.cover,s.background FROM charts c JOIN songs s ON c.sid=s.sid WHERE c.exist=1 AND s.exist=1",null)){
                String[] names={"cid","server_hash","path","mc_name","version","mode","title","artist","sid","level","promote","cover","background"};while(cursor.moveToNext()){JSONObject item=new JSONObject();for(int i=0;i<names.length;i++)item.put(names[i],cursor.getType(i)==Cursor.FIELD_TYPE_INTEGER?cursor.getLong(i):cursor.getString(i));rows.put(item);}
            }finally{db.close();}
            if(rows.length()==0)throw new IOException("服务器当前没有有效谱面，未替换清单");
            server=address;JSONObject next=new JSONObject().put("server",server).put("updated_at",System.currentTimeMillis()/1000).put("charts",rows);
            if(!oldServer.equals(server)){aliases=new JSONObject();scores=new JSONArray();save("aliases.json",aliases);save("scores.json",scores);}
            catalog=next;save("catalog.json",catalog);context.getSharedPreferences("settings",0).edit().putString("server",server).apply();
            JSONArray valid=new JSONArray();for(int i=0;i<scores.length();i++){JSONObject s=scores.getJSONObject(i);if(active(s.optInt("cid"),s.optString("server_hash")))valid.put(s);}scores=valid;save("scores.json",scores);
        }finally{if(temp.exists())temp.delete();}
    }
    public interface Reader {List<String> list(String type)throws Exception;byte[] file(String path)throws Exception;}
    public static Reader binderReader(IBinder binder){return new Reader(){
        public List<String> list(String type)throws Exception {Parcel in=Parcel.obtain(),out=Parcel.obtain();try{in.writeInterfaceToken(ReadOnlyService.DESCRIPTOR);in.writeString(type);binder.transact(1,in,out,0);out.readException();return out.createStringArrayList();}finally{in.recycle();out.recycle();}}
        public byte[] file(String path)throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();for(long offset=0;;offset+=262144){Parcel in=Parcel.obtain(),out=Parcel.obtain();byte[] b;try{in.writeInterfaceToken(ReadOnlyService.DESCRIPTOR);in.writeString(path);in.writeLong(offset);in.writeInt(262144);binder.transact(2,in,out,0);out.readException();b=out.createByteArray();}finally{in.recycle();out.recycle();}if(bytes.size()+b.length>33554432)throw new IOException("文件超出限制");bytes.write(b);if(b.length<262144)return bytes.toByteArray();}}
    };}
    private JSONObject allowed(String md5){JSONObject a=aliases.optJSONObject(md5);return a!=null&&active(a.optInt("cid"),a.optString("server_hash"))?a:null;}
    private void cacheLocalJacket(Reader reader,String path,byte[] local,int cid){
        try{JSONObject meta=new JSONObject(new String(local,StandardCharsets.UTF_8)).getJSONObject("meta");for(String key:new String[]{"cover","background"}){
            String name=meta.optString(key,"");if(name.isEmpty())continue;try{byte[] data=reader.file(path.substring(0,path.lastIndexOf('/')+1)+name);if(data.length==0||data.length>8000000)continue;
                File folder=new File(context.getFilesDir(),"jackets");folder.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(folder,cid+".img"))){out.write(data);}return;
            }catch(Exception missing){}}
        }catch(Exception unsupported){}
    }
    public String scan(Reader reader,Progress progress)throws Exception {
        List<String> localCharts=reader.list("chart");int verified=0,failed=0;JSONArray entries=charts();
        for(String path:localCharts){
          try {
            String basename=path.substring(path.lastIndexOf('/')+1);List<JSONObject> candidates=new ArrayList<>();
            for(int i=0;i<entries.length();i++){JSONObject e=entries.getJSONObject(i);if(basename.equals(e.optString("mc_name")))candidates.add(e);}
            if(candidates.isEmpty())continue;
            byte[] local=reader.file(path);String md5=ChartMatcher.md5(local);JSONObject existing=allowed(md5);if(existing!=null){cacheLocalJacket(reader,path,local,existing.optInt("cid"));verified++;continue;}
            progress.update("匹配谱库目录 "+(verified+1)+" · "+basename);
            JSONObject match=null;boolean ambiguous=false;
            for(JSONObject candidate:candidates){
                if(ChartMatcher.catalogMatches(local,basename,candidate)){if(match!=null&&match.optInt("cid")!=candidate.optInt("cid")){ambiguous=true;break;}match=candidate;}
            }
            if(match!=null&&!ambiguous){aliases.put(md5,new JSONObject(match.toString()).put("verification","catalog_identity").put("local_md5",md5));cacheLocalJacket(reader,path,local,match.optInt("cid"));verified++;}
          } catch(Exception unsupported) { failed++; }
        }
        save("aliases.json",aliases);
        List<String> replays=reader.list("replay");int imported=0,skipped=0;Collections.sort(replays);
        for(String path:replays){progress.update("筛选回放 "+(imported+skipped+1)+" / "+replays.size());if(importReplay(reader.file(path)))imported++;else skipped++;}
        return "目录匹配 "+verified+" 张谱面；收录/更新 "+imported+" 条成绩；跳过 "+skipped+" 份回放。"+(failed>0?" "+failed+" 张谱面未能识别，已排除。":"");
    }
    public boolean importReplay(byte[] raw)throws Exception {
        String hash=Proto.replayHash(raw);JSONObject chart=allowed(hash);if(chart==null)return false;
        // Only an allowlisted chart reaches score interpretation/storage.
        List<Proto.Field> replay=Proto.decode(raw);Object data=Proto.get(replay,8);if(!(data instanceof byte[]))throw new IOException("回放缺少成绩信息");
        List<Proto.Field> score=Proto.decode((byte[])data);long timestamp=Proto.integer(score,12);
        String id=chart.getInt("cid")+":"+timestamp+":"+ChartMatcher.md5(raw);
        for(int i=0;i<scores.length();i++)if(id.equals(scores.getJSONObject(i).optString("id")))return false;
        JSONObject item=new JSONObject().put("id",id).put("cid",chart.getInt("cid")).put("server_hash",chart.getString("server_hash"))
            .put("chart_md5",hash).put("title",chart.optString("title")).put("version",chart.optString("version"))
            .put("player",Proto.text(replay,6)).put("score_candidate",Proto.integer(score,5)).put("accuracy_candidate",Proto.real(score,11))
            .put("played_at",timestamp).put("verification",chart.optString("verification","catalog_identity")).put("field_mapping_verified",false).put("uploaded",false);
        JSONObject rawFields=new JSONObject();for(Proto.Field f:score)if(f.value instanceof Number)rawFields.put(Integer.toString(f.number),f.value);item.put("raw_score_fields",rawFields);
        scores.put(item);save("scores.json",scores);return true;
    }
    public int importUsb()throws Exception {
        File dir=new File(context.getFilesDir(),"inbox");File manifest=new File(dir,"manifest.json");if(!manifest.exists())return 0;
        JSONObject bundle=new JSONObject(new String(read(new FileInputStream(manifest),16000000),StandardCharsets.UTF_8));
        JSONObject incoming=bundle.getJSONObject("catalog");if(!server.equals(incoming.optString("server")))throw new IOException("USB 清单属于另一服务器");
        catalog=incoming;save("catalog.json",catalog);JSONObject incomingAliases=bundle.getJSONObject("aliases");Iterator<String> keys=incomingAliases.keys();
        while(keys.hasNext()){String h=keys.next();JSONObject a=incomingAliases.getJSONObject(h);if(active(a.optInt("cid"),a.optString("server_hash")))aliases.put(h,a);}save("aliases.json",aliases);
        JSONArray valid=new JSONArray();for(int i=0;i<scores.length();i++){JSONObject s=scores.getJSONObject(i);if(active(s.optInt("cid"),s.optString("server_hash")))valid.put(s);}scores=valid;
        int count=0;File[] files=dir.listFiles();if(files!=null)for(File f:files){if(f.getName().endsWith(".mrv")){if(importReplay(read(new FileInputStream(f),33554432)))count++;f.delete();}
            else if(f.getName().matches("jacket_\\d+\\.img")){int cid=Integer.parseInt(f.getName().substring(7,f.getName().length()-4));boolean exists=false;for(int j=0;j<charts().length();j++)if(charts().getJSONObject(j).optInt("cid")==cid){exists=true;break;}if(exists){File jackets=new File(context.getFilesDir(),"jackets");jackets.mkdirs();File dest=new File(jackets,cid+".img");if(dest.exists())dest.delete();if(!f.renameTo(dest))throw new IOException("曲绘缓存失败");}else f.delete();}}
        save("scores.json",scores);manifest.delete();return count;
    }
    public JSONObject export()throws Exception {JSONArray valid=new JSONArray();for(int i=0;i<scores.length();i++){JSONObject s=scores.getJSONObject(i);if(active(s.optInt("cid"),s.optString("server_hash")))valid.put(s);}return new JSONObject().put("schema",1).put("server",server).put("field_mapping_verified",false).put("scores",valid);}
}
