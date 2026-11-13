package cn.gmiya.malodylink;
import android.content.*;
import android.graphics.*;
import android.util.Base64;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Avatar is separate from immutable score batches. Empty image explicitly removes it. */
final class CloudAvatar {
    static String sync(Context c,Engine engine,OpenListScoreRemote remote,boolean upload){
        String qq=engine.profile;android.content.SharedPreferences prefs=c.getSharedPreferences("appearance",0);long revision=prefs.getLong("avatar_rev_"+qq,0),ack=prefs.getLong("avatar_ack_"+qq,0);
        boolean attempted=false;try{
            if(!qq.equals(ScoreStore.profile(c)))throw new IOException("存档已切换");
            Bitmap image=LocalPlayerProfile.avatarBitmap(c);
            if(upload&&(image!=null||revision>ack)){attempted=true;
                byte[] bytes=new byte[0];if(image!=null){try{ByteArrayOutputStream out=new ByteArrayOutputStream();Bitmap resized=Bitmap.createScaledBitmap(image,384,384,true);resized.compress(Bitmap.CompressFormat.JPEG,85,out);if(resized!=image)resized.recycle();bytes=out.toByteArray();}finally{image.recycle();}}if(bytes.length>1048576)throw new IOException("头像过大");
                JSONObject doc=new JSONObject().put("schema",1).put("qq",qq).put("server",engine.server).put("image",Base64.encodeToString(bytes,Base64.NO_WRAP)).put("sha256",CloudRecords.sha256(bytes));byte[] packed=doc.toString().getBytes(StandardCharsets.UTF_8);remote.putAvatar(qq,packed);
                if(!java.util.Arrays.equals(packed,remote.getPath("/"+CloudRecords.qq(qq)+"/player-avatar.json",1500000)))throw new IOException("头像回读失败");
                if(qq.equals(ScoreStore.profile(c))&&prefs.getLong("avatar_rev_"+qq,0)==revision)prefs.edit().putLong("avatar_ack_"+qq,revision).apply();return " 头像已上传。";
            }
            if(image!=null)image.recycle();if(revision>ack)return " 本机头像待上传。";
            JSONObject doc=new JSONObject(new String(remote.getPath("/"+CloudRecords.qq(qq)+"/player-avatar.json",1500000),StandardCharsets.UTF_8));if(doc.optInt("schema")!=1||!qq.equals(doc.optString("qq"))||!engine.server.equals(doc.optString("server")))throw new IOException("头像身份不匹配");
            byte[] bytes=Base64.decode(doc.getString("image"),Base64.NO_WRAP);if(bytes.length>1048576||!CloudRecords.sha256(bytes).equals(doc.getString("sha256")))throw new IOException("头像校验失败");Bitmap avatar=bytes.length==0?null:LocalPlayerProfile.prepareAvatar(new ByteArrayInputStream(bytes));
            try{if(qq.equals(ScoreStore.profile(c))&&prefs.getLong("avatar_rev_"+qq,0)==revision){LocalPlayerProfile.save(c,LocalPlayerProfile.nickname(c,""),avatar,true);prefs.edit().putLong("avatar_ack_"+qq,prefs.getLong("avatar_rev_"+qq,0)).apply();}}finally{if(avatar!=null)avatar.recycle();}return " 云端头像已恢复。";
        }catch(Exception unavailable){return attempted?" 头像同步未完成，下次云端同步会重试。":"";}
    }
}
