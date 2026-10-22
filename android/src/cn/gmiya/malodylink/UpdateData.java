package cn.gmiya.malodylink;

import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Bounded release metadata. The APK path is derived from its digest. */
public final class UpdateData {
    public static final int MAX_APK=64*1024*1024;
    public final int code;public final String name,digest,notes;public final long size;
    private UpdateData(JSONObject doc)throws Exception {
        long version=doc.getLong("version_code");code=(int)version;name=doc.getString("version_name");digest=doc.getString("sha256");notes=doc.getString("notes");size=doc.getLong("size");
        if(!(doc.get("version_code") instanceof Number)||doc.getDouble("version_code")!=version||!(doc.get("size") instanceof Number)||doc.getDouble("size")!=size||version<=0||version>Integer.MAX_VALUE||!name.matches("[0-9]+(?:\\.[0-9]+){1,3}")||name.length()>32||!digest.matches("[a-f0-9]{64}")||size<=0||size>MAX_APK||notes.length()>4000)throw new IOException("更新信息无效");
    }
    public static UpdateData read(byte[] bytes,String server)throws Exception {
        if(bytes.length>32000)throw new IOException("更新信息超过容量");JSONObject doc=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(doc.getInt("schema")!=1||!server.equals(doc.getString("server"))||!"cn.gmiya.malodylink".equals(doc.getString("package")))throw new IOException("更新来源或应用不匹配");
        return new UpdateData(doc);
    }
    public String path(){return "/_updates/"+digest+".apk";}
}
