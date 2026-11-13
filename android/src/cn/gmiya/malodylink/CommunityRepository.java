package cn.gmiya.malodylink;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CommunityRepository {
    private static String revision(Engine engine)throws Exception{return RankingIndex.revision(engine.server,engine.charts());}
    private static File file(Context c,Engine engine,String name)throws Exception{return new File(c.getFilesDir(),"community/"+CloudRecords.sha256((engine.server+revision(engine)).getBytes(StandardCharsets.UTF_8))+"/"+name+".json");}
    private static OpenListScoreRemote open(Context c,Engine engine)throws Exception {if(!engine.server.equals(RankConnection.config(c).getString("server")))throw new IOException("当前谱库暂未接入此社区");return RankConnection.open(c);}
    public static CommunityData.Boards cachedBoards(Context c,Engine engine){try{CommunityData.Boards boards=CommunityData.boards(ScoreStore.read(file(c,engine,"boards")),engine.server,revision(engine));boards.cached=true;return boards;}catch(Exception absent){return null;}}
    public static CommunityData.Boards boards(Context c,Engine engine,boolean force)throws Exception {
        File target=file(c,engine,"boards");CommunityData.Boards cache=cachedBoards(c,engine);
        if(!force&&cache!=null&&System.currentTimeMillis()-target.lastModified()<60000)return cache;
        byte[] bytes=open(c,engine).getPath("/_community/v1/boards.json",CommunityData.MAX_BYTES);
        CommunityData.Boards boards=CommunityData.boardsWithRefresh(bytes,engine.server,revision(engine),()->{
            if(!ScoreSyncGate.enter("catalogue"))throw new IOException("正在同步成绩，请完成后刷新榜单");
            try{
                Engine latest=new Engine(c);
                if(!latest.profile.equals(engine.profile)||!latest.server.equals(engine.server))throw new IOException("存档或服务器已切换，请重新打开榜单");
                engine.scores=latest.scores;engine.aliases=latest.aliases;
                DefaultCatalog.refresh(c,engine,true,progress->{});return revision(engine);
            }finally{ScoreSyncGate.leave("catalogue");}
        });
        if(System.currentTimeMillis()-boards.doc.getLong("updated")>300000)throw new IOException("社区榜单暂未更新，请稍后刷新");
        // A catalogue refresh changes the namespace: never write new boards to the old cache.
        ScoreStore.write(file(c,engine,"boards"),boards.doc);return boards;
    }
    public static JSONObject cachedSong(Context c,Engine engine,String topic){try{return CommunityData.song(ScoreStore.read(file(c,engine,CommunityData.topic(topic))),engine.server,revision(engine),topic);}catch(Exception absent){return null;}}
    public static JSONObject song(Context c,Engine engine,String topic)throws Exception {
        topic=CommunityData.topic(topic);JSONObject doc=CommunityData.song(open(c,engine).getPath("/_community/v1/songs/"+topic+".json",CommunityData.MAX_BYTES),engine.server,revision(engine),topic);ScoreStore.write(file(c,engine,topic),doc);return doc;
    }
    public static JSONObject ownReview(Context c,Engine engine,String topic){try{return new JSONObject(new String(ScoreStore.read(file(c,engine,CommunityData.topic(topic)+"-"+CloudRecords.qq(engine.profile)+"-own")),StandardCharsets.UTF_8));}catch(Exception absent){return null;}}
    public static void post(Context c,Engine engine,JSONObject review)throws Exception {
        CommunityData.validateReview(review);String qq=CloudRecords.qq(review.getString("qq")),topic=CommunityData.topic(review.getString("topic"));
        if(!qq.equals(ScoreStore.profile(c))||!engine.server.equals(review.getString("server")))throw new IOException("存档已切换，请重新打开评论页面");
        byte[] bytes=ChartMatcher.canonical(review).getBytes(StandardCharsets.UTF_8);if(bytes.length>16384)throw new IOException("评论超过容量");
        String dir="/_community/v1/reviews/"+topic+"/"+qq,name="review-"+CloudRecords.sha256(bytes)+".json";OpenListScoreRemote remote=open(c,engine);
        OpenListScoreRemote.ok(remote.api("/api/fs/mkdir",new JSONObject().put("path",dir)));Map<String,String> headers=new HashMap<>();headers.put("Content-Type","application/json; charset=utf-8");headers.put("File-Path",dir+"/"+name);headers.put("As-Task","false");
        OpenListScoreRemote.ok(remote.readJson(remote.baseAddress()+"/api/fs/put","PUT",bytes,headers,true,100000));
        if(!Arrays.equals(bytes,remote.getPath(dir+"/"+name,16384)))throw new IOException("评论回读未完成，请稍后刷新确认");
        ScoreStore.write(file(c,engine,topic+"-"+qq+"-own"),review);
    }
}
