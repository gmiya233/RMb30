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
        chart.put("server_hash","hash");
        ReplayScanCache individual=new ReplayScanCache(new JSONObject(),"server",charts,aliases,cloud);
        individual.checked("one","stamp","md5","a|hash");individual.checked("unknown","stamp","unknown-hash","");
        JSONObject individualSaved=individual.snapshot(Arrays.asList("one","unknown"));
        JSONArray expanded=new JSONArray(charts.toString()).put(new JSONObject().put("cid",2).put("server_hash","new").put("title","New song"));
        JSONObject extraAliases=new JSONObject(aliases.toString()).put("another",new JSONObject().put("cid",2).put("server_hash","new"));
        ReplayScanCache individualNext=new ReplayScanCache(individualSaved,"server",expanded,extraAliases,cloud);
        check(individualNext.unchanged("one","stamp")&&individualNext.unchanged("unknown","stamp"),"unrelated chart and alias additions do not reread existing or unknown replays");
        check(!individualNext.unchanged("one","new-stamp"),"changed replay metadata still forces read");
        check(!new ReplayScanCache(individualSaved,"server",expanded,extraAliases,new JSONArray().put(score("b"))).unchanged("one","stamp"),"only a replay whose own result disappeared is reread");
        extraAliases.put("unknown-hash",new JSONObject().put("cid",2).put("server_hash","new"));
        ReplayScanCache newlyMatched=new ReplayScanCache(individualSaved,"server",expanded,extraAliases,cloud);
        check(!newlyMatched.unchanged("unknown","stamp")&&newlyMatched.unchanged("one","stamp"),"learning an unknown replay chart rereads that replay only");
        check(!new ReplayScanCache(individualSaved,"other",expanded,extraAliases,cloud).unchanged("one","stamp"),"replay caches remain isolated by server");
        ReplayScanCache bulk=new ReplayScanCache(new JSONObject(),"server",charts,aliases,cloud);List<String> bulkPaths=new ArrayList<>();
        for(int i=0;i<2759;i++){String p="large/"+i;bulkPaths.add(p);bulk.checked(p,"100:1:1","md5","a|hash");}
        JSONObject bulkSaved=bulk.snapshot(bulkPaths);ReplayScanCache bulkNext=new ReplayScanCache(bulkSaved,"server",expanded,extraAliases,cloud);int payloadReads=0;
        for(String p:bulkPaths)if(!bulkNext.unchanged(p,"100:1:1"))payloadReads++;
        check(payloadReads==0,"2759 unchanged replay payloads remain skipped after unrelated catalogue and alias changes");
        ScanCache chartCache=new ScanCache(new JSONObject(),revision,null);chartCache.checked("chart/song.mc","stamp");
        check(new ScanCache(chartCache.snapshot(revision,null,Collections.singletonList("chart/song.mc")),revision,null).unchanged("chart/song.mc","stamp"),"unchanged chart matching is persisted separately");
    }
}
