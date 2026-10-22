package cn.gmiya.malodylink;

import android.content.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class CloudScoreSync {
    public static boolean automatic(Context c){return !ScoreStore.profile(c).isEmpty()&&c.getSharedPreferences("cloud",0).getBoolean("auto",true);}
    private static OpenListScoreRemote remote(Context c)throws Exception {JSONObject config=new JSONObject(new String(Engine.read(c.getAssets().open("default-rank.json"),16000),StandardCharsets.UTF_8));return new OpenListScoreRemote(config.getString("server"),config.getString("username"),config.getString("password"),config.getString("root"));}
    /** Caller owns ScoreSyncGate for both local data and cloud data. */
    public static String merge(Context context,Engine engine,boolean upload,Engine.Progress progress)throws Exception {
        String qq=CloudRecords.qq(engine.profile);if(!qq.equals(ScoreStore.profile(context)))throw new IOException("QQ存档已切换，请重新同步");
        try{
            progress.update("连接 QQ "+qq+" 的云存档…");OpenListScoreRemote source=remote(context);
            File cache=new File(context.getCacheDir(),"cloud-batches/"+CloudRecords.sha256(engine.server.getBytes(StandardCharsets.UTF_8))+"/"+qq);cache.mkdirs();
            CloudArchive.Remote cached=new CloudArchive.Remote(){
                public java.util.List<String> list(String id,boolean create)throws Exception{return source.list(id,create);}
                public byte[] get(String id,String name)throws Exception {
                    File f=new File(cache,name);if(f.exists()){byte[] b=Engine.read(new FileInputStream(f),CloudRecords.MAX_BYTES);if(name.equals("scores-"+CloudRecords.sha256(b)+".json"))return b;}
                    byte[] bytes=source.get(id,name);if(!name.equals("scores-"+CloudRecords.sha256(bytes)+".json"))throw new IOException("云端文件尚未完整写入，请稍后重试");
                    try(FileOutputStream out=new FileOutputStream(f)){out.write(bytes);}return bytes;
                }
                public void put(String id,String name,byte[] bytes)throws Exception {source.put(id,name,bytes);}
            };
            CloudArchive.Result result=CloudArchive.sync(cached,qq,engine.server,engine.scores,engine.charts(),upload,progress::update);
            if(!qq.equals(ScoreStore.profile(context)))throw new IOException("QQ存档已切换，本次未写入本地成绩");
            ScoreStore.write(ScoreStore.file(context,engine.profile,"scores.json"),result.merged.scores);engine.scores=result.merged.scores;
            String message=(upload?"云端合并完成":"云端拉取完成")+"：本机新增 "+result.downloaded+" 条，上传 "+result.uploaded+" 条，共 "+engine.scores.length()+" 条。"+
                (result.merged.conflicts>0?" 同ID冲突 "+result.merged.conflicts+" 条，保留本机版本；云端原件保留。":"")+
                (result.merged.skipped>0?" 跳过不属于当前谱库或格式不符的记录。":"");
            context.getSharedPreferences("cloud",0).edit().putLong("last_sync",System.currentTimeMillis()).putString("last_result",message).putBoolean("last_ok",true).apply();return message;
        }catch(Exception e){context.getSharedPreferences("cloud",0).edit().putString("last_result","云端同步未完成，本机成绩保留。"+(e.getMessage()==null?"请稍后重试。":e.getMessage())).putBoolean("last_ok",false).apply();throw e;}
    }
    public static String run(Context c,boolean upload,Engine.Progress callback)throws Exception {
        if(!ScoreSyncGate.enter("cloud"))throw new IllegalStateException("其他同步正在进行，请稍候");
        Engine.Progress progress=s->{LocalScoreSync.currentProgress=s;callback.update(s);};
        try{Engine engine=new Engine(c);String warning="";try{DefaultCatalog.refresh(c,engine,false,progress);}catch(Exception unavailable){warning="谱库更新未完成，按缓存目录筛选。";}return warning+merge(c,engine,upload,progress);}
        finally{LocalScoreSync.currentProgress="";ScoreSyncGate.leave("cloud");c.sendBroadcast(new Intent(LocalScoreSync.UPDATED).setPackage(c.getPackageName()));}
    }
}
