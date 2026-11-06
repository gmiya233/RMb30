package cn.gmiya.malodylink;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Canonical current-chart artwork. Legacy CID-only thumbnails are never trusted. */
final class ServerArtwork {
    private static JSONObject index;private static long checked;private static String namespace="";
    private static synchronized JSONObject index(Context c)throws Exception {
        String server=RankConnection.config(c).getString("server");
        if(!namespace.equals(server)){index=null;checked=0;namespace=server;}
        if(System.currentTimeMillis()-checked>300000){checked=System.currentTimeMillis();try{
            JSONObject doc=new JSONObject(new String(RankConnection.open(c).getPath("/_song_art/v2/index.json",4000000),StandardCharsets.UTF_8));
            if(doc.optInt("schema")!=2||!server.equals(doc.optString("server")))throw new IOException("曲绘来源不匹配");index=doc.getJSONObject("charts");
        }catch(Exception e){if(index==null)throw e;}}
        if(index==null)throw new IOException("曲绘索引暂不可用");return index;
    }
    static byte[] get(Context c,int cid,String hash)throws Exception {
        JSONObject row=index(c).optJSONObject(Integer.toString(cid));if(row==null||!hash.equals(row.optString("server_hash")))throw new IOException("曲绘与当前谱面不匹配");
        String digest=row.getString("image");if(!digest.matches("[a-f0-9]{64}"))throw new IOException("无效曲绘");
        File folder=new File(c.getCacheDir(),"server-art-v2");folder.mkdirs();File file=new File(folder,digest+".jpg");
        if(file.exists()){byte[] data=Engine.read(new FileInputStream(file),1048576);if(CloudRecords.sha256(data).equals(digest))return data;}
        byte[] data=RankConnection.open(c).getPath("/_song_art/v2/"+digest+".jpg",1048576);if(!CloudRecords.sha256(data).equals(digest))throw new IOException("曲绘校验失败");
        synchronized(ServerArtwork.class){File temp=new File(folder,digest+".tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(data);}if(!temp.renameTo(file))temp.delete();}return data;
    }
}
