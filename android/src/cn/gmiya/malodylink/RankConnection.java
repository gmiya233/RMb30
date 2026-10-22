package cn.gmiya.malodylink;

import android.content.Context;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

public final class RankConnection {
    public static OpenListScoreRemote open(Context c)throws Exception{JSONObject config=config(c);return new OpenListScoreRemote(config.getString("server"),config.getString("username"),config.getString("password"),config.getString("root"));}
    public static JSONObject config(Context c)throws Exception{return new JSONObject(new String(Engine.read(c.getAssets().open("default-rank.json"),16000),StandardCharsets.UTF_8));}
}
