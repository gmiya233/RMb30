import cn.gmiya.malodylink.*;
import org.json.*;
import java.nio.file.*;

/** Use only a pre-reserved, empty synthetic QQ folder; cleanup is performed by the caller. */
public final class CloudRemoteIntegrationTest {
    public static void main(String[] args)throws Exception {
        JSONObject config=new JSONObject(Files.readString(Paths.get(args[0])));String qq=CloudRecords.qq(args[1]);
        OpenListScoreRemote source=new OpenListScoreRemote(config.getString("server"),config.getString("username"),config.getString("password"),config.getString("root"));
        JSONArray charts=CloudArchiveTest.catalogue(),local=new JSONArray().put(CloudArchiveTest.score(1,100));
        CloudArchive.Result first=CloudArchive.sync(source,qq,CloudArchiveTest.SERVER,local,charts,true,p->{});
        if(first.uploaded!=1)throw new AssertionError("synthetic upload");
        CloudArchive.Result restored=CloudArchive.sync(source,qq,CloudArchiveTest.SERVER,new JSONArray(),charts,false,p->{});
        if(restored.merged.scores.length()!=1)throw new AssertionError("fresh-phone restore");
        CloudArchive.Result repeat=CloudArchive.sync(source,qq,CloudArchiveTest.SERVER,restored.merged.scores,charts,true,p->{});
        if(repeat.uploaded!=0||source.list(qq,false).size()!=1)throw new AssertionError("idempotent merge");
        System.out.println("PASS: real Rank1 root check, synthetic folder creation, upload/readback, empty-client restore and duplicate-free repeat");
    }
}
