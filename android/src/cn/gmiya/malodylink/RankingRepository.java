package cn.gmiya.malodylink;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads only the requested board's compact indices, never player history archives. */
public final class RankingRepository {
    public static final class Snapshot {public final List<RankingData.Player> players=new ArrayList<>();public long updated;public int failed,folders,cid;public boolean cached,stale;}
    private static final Object LOCK=new Object();
    private static File file(Context c,Engine engine,int cid)throws Exception{return new File(c.getFilesDir(),"rankings/"+RankingIndex.revision(engine.server,engine.charts())+"-"+cid+".json");}
    public static Snapshot cached(Context c,Engine engine)throws Exception{return cached(c,engine,0);}
    public static Snapshot cached(Context c,Engine engine,int cid)throws Exception{return read(file(c,engine,cid));}
    private static Snapshot read(File f){try{if(!ScoreStore.exists(f))return null;JSONObject o=new JSONObject(new String(ScoreStore.read(f),StandardCharsets.UTF_8));Snapshot s=new Snapshot();s.updated=o.getLong("updated");s.failed=o.optInt("failed");s.folders=o.optInt("folders");s.cid=o.optInt("cid");s.stale=o.optBoolean("stale");s.cached=true;JSONArray players=o.getJSONArray("players");for(int i=0;i<players.length();i++)s.players.add(RankingData.decode(players.getJSONObject(i)));return s;}catch(Exception corrupt){return null;}}
    public static void invalidate(Context c,Engine engine)throws Exception {File dir=new File(c.getFilesDir(),"rankings");String prefix=RankingIndex.revision(engine.server,engine.charts())+"-";File[] files=dir.listFiles();if(files!=null)for(File f:files)if(f.getName().startsWith(prefix)&&f.getName().endsWith(".json")){Snapshot s=read(f);if(s!=null){s.stale=true;save(f,s);}}}
    private static void save(File target,Snapshot s)throws Exception {JSONArray rows=new JSONArray();for(RankingData.Player p:s.players)rows.put(RankingData.encode(p));ScoreStore.write(target,new JSONObject().put("updated",s.updated).put("failed",s.failed).put("folders",s.folders).put("cid",s.cid).put("stale",s.stale).put("players",rows));}
    public static Snapshot refresh(Context c,Engine engine,boolean force,Engine.Progress progress)throws Exception{return refresh(c,engine,force,0,progress);}
    public static Snapshot refresh(Context c,Engine engine,boolean force,int cid,Engine.Progress progress)throws Exception{synchronized(LOCK){
        File target=file(c,engine,cid);Snapshot previous=read(target);if(!force&&previous!=null&&!previous.stale&&System.currentTimeMillis()-previous.updated<300000)return previous;
        if(!engine.server.equals(RankConnection.config(c).getString("server")))throw new IOException("当前谱库不属于此云端排行榜服务器");
        String revision=RankingIndex.revision(engine.server,engine.charts());OpenListScoreRemote connection=RankConnection.open(c);
        progress.update("读取服务器排行汇总…");
        try{
            RankingSnapshot board=RankingSnapshot.read(connection.getPath(RankingSnapshot.path(revision,cid),RankingSnapshot.MAX_BYTES),engine.server,revision,cid);
            // Old snapshots may outlive a stopped worker. Fall back to live legacy indices.
            if(System.currentTimeMillis()-board.updated>120000)throw new IOException("服务器汇总尚未更新");
            Snapshot fast=new Snapshot();fast.cid=cid;fast.updated=board.updated;fast.failed=board.failed;fast.folders=board.folders;fast.players.addAll(board.players);save(target,fast);return fast;
        }catch(Exception unavailable){progress.update("汇总暂不可用，读取兼容排行…");}
        RankingIndex.Remote source=new OpenListRankingRemote(connection);List<String> ids=source.directories(RankingIndex.board(revision,cid));Snapshot result=new Snapshot();result.cid=cid;result.folders=ids.size();Map<String,RankingData.Player> fallback=new HashMap<>();if(previous!=null)for(RankingData.Player p:previous.players)fallback.put(p.qq,p);
        long[] downloaded={0};RankingIndex.Remote remote=new RankingIndex.Remote(){
            public List<String> directories(String p)throws Exception{return source.directories(p);}public List<String> files(String p)throws Exception{return source.files(p);}
            public void put(String p,String n,byte[] b)throws Exception{throw new IOException("浏览排行榜不会上传");}public void remove(String p,List<String> n)throws Exception{throw new IOException("浏览排行榜不会清理索引");}
            public byte[] get(String path,String name)throws Exception{File folder=new File(c.getCacheDir(),"rank-index/"+path.substring("/_rankings/v1/".length()));folder.mkdirs();File f=new File(folder,name);if(f.exists()){byte[] b=CloudRecords.read(new FileInputStream(f),RankingIndex.MAX_BYTES);if(name.equals("entry-"+CloudRecords.sha256(b)+".json"))return b;}if(downloaded[0]>=32L*1024*1024)throw new IOException("本次索引下载超过32MiB，请稍后重试");byte[] b=source.get(path,name);downloaded[0]+=b.length;if(!name.equals("entry-"+CloudRecords.sha256(b)+".json"))throw new IOException("排行索引校验失败");try(FileOutputStream out=new FileOutputStream(f)){out.write(b);}return b;}
        };
        int success=0;for(int i=0;i<ids.size();i++){String qq=ids.get(i);progress.update("读取排行摘要 "+(i+1)+" / "+ids.size());try{RankingIndex.Heads heads=RankingIndex.read(remote,engine.server,revision,cid,qq,engine.charts());if(heads.scores.length()>0)result.players.add(RankingIndex.player(qq,heads,engine.charts()));success++;}catch(Exception unavailable){result.failed++;if(fallback.containsKey(qq))result.players.add(fallback.get(qq));}}
        if(!ids.isEmpty()&&success==0)throw new IOException("本次未能读取玩家排行摘要，保留上次缓存");result.updated=System.currentTimeMillis();save(target,result);return result;
    }}
}
