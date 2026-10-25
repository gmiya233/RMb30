import cn.gmiya.malodylink.*;
import org.json.*;
import java.util.*;

public final class ScanCacheTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);System.out.println("PASS: "+message);}
    private static JSONObject score(String id){return new JSONObject().put("id",id).put("server_hash","hash").put("uploaded",false);}
    public static void main(String[] args)throws Exception {
        JSONObject chart=new JSONObject().put("cid",1).put("server_hash","hash").put("mc_name","song.mc").put("mode",7).put("path","song").put("title","Song");
        JSONArray charts=new JSONArray().put(chart),scores=new JSONArray().put(score("b")).put(score("a"));
        JSONObject aliases=new JSONObject().put("md5",new JSONObject().put("cid",1).put("server_hash","hash").put("cover","old"));
        String revision=ScanCache.revision("server",charts,aliases);
        ScanCache cache=new ScanCache(new JSONObject(),revision,scores);
        List<String> paths=new ArrayList<>();for(int i=0;i<2749;i++){String path="replay/"+i;paths.add(path);cache.checked(path,"100:1:1");}
        JSONObject saved=cache.snapshot(revision,scores,paths);
        // Actual cloud behaviour sorts and rewrites metadata; additions should also be harmless.
        JSONArray cloud=new JSONArray().put(score("a").put("uploaded",true).put("title","Updated")).put(score("b")).put(score("c"));
        check(!scores.toString().equals(cloud.toString()),"cloud merge changes the old whole-JSON fingerprint");
        ScanCache second=new ScanCache(saved,revision,cloud);int reads=0;
        for(String path:paths)if(!second.unchanged(path,"100:1:1"))reads++;
        check(reads==0,"2749 checked replays need zero reads after cloud reorder/enrichment/addition");
        check(!second.unchanged("replay/0","101:2:2"),"modified replay is read again");
        check(!second.unchanged("replay/new","100:1:1"),"new replay is read");
        check(!second.unchanged("replay/0",""),"reader without reliable fingerprint never skips");
        check(!new ScanCache(saved,revision,new JSONArray().put(score("a"))).unchanged("replay/0","100:1:1"),"removed local score invalidates decisions so it can be restored");
        chart.put("download_count",999).put("cover","new");aliases.getJSONObject("md5").put("cover","new");
        check(revision.equals(ScanCache.revision("server",charts,aliases)),"display metadata changes preserve cache");
        chart.put("server_hash","replacement");String changed=ScanCache.revision("server",charts,aliases);
        check(!revision.equals(changed)&&!new ScanCache(saved,changed,cloud).unchanged("replay/0","100:1:1"),"revised catalogue invalidates negative and positive decisions");
        check(!new ScanCache(saved,ScanCache.revision("other",charts,aliases),cloud).unchanged("replay/0","100:1:1"),"server switch invalidates decisions");
        JSONObject pruned=second.snapshot(revision,cloud,Collections.singletonList("replay/0"));
        check(pruned.getJSONObject("files").length()==1,"deleted files are pruned");
        ScanCache partial=new ScanCache(new JSONObject(),revision,cloud);partial.checked("done","stamp");
        ScanCache resumed=new ScanCache(partial.snapshot(revision,cloud,Arrays.asList("done","failed")),revision,cloud);
        check(resumed.unchanged("done","stamp")&&!resumed.unchanged("failed","stamp"),"interrupted scan retains completed files but retries unread failures");
        ScanCache chartCache=new ScanCache(new JSONObject(),revision,null);chartCache.checked("chart/song.mc","stamp");
        check(new ScanCache(chartCache.snapshot(revision,null,Collections.singletonList("chart/song.mc")),revision,null).unchanged("chart/song.mc","stamp"),"unchanged chart matching is persisted separately");
    }
}
