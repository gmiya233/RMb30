package cn.gmiya.malodylink;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import org.json.JSONObject;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** The distributed read-only account fetches only malody.db over WebDAV GET. */
public final class DefaultCatalog {
    private static final long INTERVAL=6*60*60*1000L;
    private static JSONObject config(Context c)throws Exception {return new JSONObject(new String(Engine.read(c.getAssets().open("default-reader.json"),16000),StandardCharsets.UTF_8));}
    public static synchronized String refresh(Context context,Engine engine,boolean force,Engine.Progress progress)throws Exception {
        JSONObject config=config(context);SharedPreferences settings=context.getSharedPreferences("settings",0);
        if(!force&&(!settings.getBoolean("default_reader",true)||!engine.server.equals(config.getString("server"))))return "";
        if(!force&&System.currentTimeMillis()-settings.getLong("default_catalog_updated",0)<INTERVAL)return "";
        progress.update("正在更新谱库目录…");
        URL url=new URL(config.getString("database_url"));if(!url.getProtocol().equals("http")&&!url.getProtocol().equals("https"))throw new IOException("无效谱库下载地址");
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(12000);connection.setReadTimeout(20000);
        String auth=config.getString("username")+":"+config.getString("password");
        connection.setRequestProperty("Authorization","Basic "+Base64.encodeToString(auth.getBytes(StandardCharsets.UTF_8),Base64.NO_WRAP));
        try{
            if(connection.getResponseCode()!=200)throw new IOException("谱库下载失败（HTTP "+connection.getResponseCode()+"）");
            byte[] bytes=Engine.read(connection.getInputStream(),16000000);engine.installDefaultCatalogue(bytes,config.getString("server"));
            settings.edit().putLong("default_catalog_updated",System.currentTimeMillis()).apply();
        }finally{connection.disconnect();}
        return "谱库已更新："+engine.charts().length()+" 张有效谱面。";
    }
}
