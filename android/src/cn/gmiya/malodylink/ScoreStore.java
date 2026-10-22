package cn.gmiya.malodylink;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** QQ archives have separate local score/index files; catalogue and aliases remain shared. */
public final class ScoreStore {
    private static final Object LOCK=new Object();
    public static String profile(Context c){String qq=c.getSharedPreferences("cloud",0).getString("qq","");return qq.matches("[1-9][0-9]{4,11}")?qq:"";}
    public static File file(Context c,String profile,String name){
        File root=c.getFilesDir();if(!profile.isEmpty()&&(name.equals("scores.json")||name.equals("replay-index.json")))root=new File(root,"profiles/"+CloudRecords.qq(profile));return new File(root,name);
    }
    public static boolean exists(File f){return f.exists()||new File(f.getPath()+".bak").exists();}
    public static byte[] read(File f)throws Exception {synchronized(LOCK){return Engine.read(new AtomicFile(f).openRead(),16000000);}}
    public static void write(File f,Object value)throws Exception {synchronized(LOCK){File parent=f.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("无法创建本地存档目录");AtomicFile atomic=new AtomicFile(f);FileOutputStream out=null;try{out=atomic.startWrite();out.write(value.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(Exception e){if(out!=null)atomic.failWrite(out);throw e;}}}
    public static JSONArray scores(Context c,String profile)throws Exception {File f=file(c,profile,"scores.json");return exists(f)?new JSONArray(new String(read(f),StandardCharsets.UTF_8)):new JSONArray();}
    public static void bind(Context c,String value,JSONArray charts)throws Exception {String qq=CloudRecords.qq(value);synchronized(LOCK){
        android.content.SharedPreferences p=c.getSharedPreferences("cloud",0);
        if(!p.getBoolean("legacy_claimed",false)){
            JSONArray source=scores(c,""),target=scores(c,qq);CloudRecords.Merged merged=CloudRecords.merge(target,source,new CloudRecords.Catalogue(charts));
            if(merged.scores.length()>0)write(file(c,qq,"scores.json"),merged.scores);
        }
        p.edit().putString("qq",qq).putBoolean("legacy_claimed",true).putBoolean("auto",true).putLong("last_sync",0).putString("last_result","等待首次云端同步").apply();
    }}
}
