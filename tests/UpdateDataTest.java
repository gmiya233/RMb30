import cn.gmiya.malodylink.*;
import org.json.*;
import java.nio.charset.StandardCharsets;

public final class UpdateDataTest {
    private static final String SERVER="https://fixture.invalid";
    static byte[] bytes(JSONObject value){return value.toString().getBytes(StandardCharsets.UTF_8);}
    static JSONObject fixture()throws Exception{return new JSONObject().put("schema",1).put("server",SERVER).put("package","cn.gmiya.malodylink").put("version_code",18).put("version_name","1.5.2").put("sha256",String.join("",java.util.Collections.nCopies(64,"a"))).put("size",9000000).put("notes","Synthetic release fixture");}
    static void rejects(String key,Object value)throws Exception {JSONObject doc=fixture().put(key,value);try{UpdateData.read(bytes(doc),SERVER);throw new AssertionError("Invalid metadata accepted: "+key);}catch(java.io.IOException expected){}}
    public static void main(String[] args)throws Exception {
        UpdateData release=UpdateData.read(bytes(fixture()),SERVER);if(release.code!=18||!release.name.equals("1.5.2")||!release.path().matches("/_updates/[a-f0-9]{64}\\.apk"))throw new AssertionError("Release path or version mismatch");
        rejects("package","other.app");rejects("server","https://other.invalid");rejects("sha256","../wrong.apk");rejects("version_code",0);rejects("version_code",18.5);rejects("version_code",2147483648L);rejects("size",0);rejects("size",UpdateData.MAX_APK+1);rejects("size",1.2);rejects("version_name","../bad");rejects("notes",String.join("",java.util.Collections.nCopies(4001,"x")));
        System.out.println("PASS: update package/server identity, bounded metadata, digest-only APK path and integral versions/sizes");
    }
}
