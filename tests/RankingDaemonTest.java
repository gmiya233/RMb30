import cn.gmiya.malodylink.*;
import org.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class RankingDaemonTest {
    static void check(boolean b,String name){if(!b)throw new AssertionError(name);}
    public static void main(String[] args)throws Exception {
        Path temp=Files.createTempDirectory(Paths.get("build"),"ranking-fixture-");Path root=Files.createDirectory(temp.resolve("archives")),state=temp.resolve("state"),cat=temp.resolve("catalog.json");
        String server=RankingIndexTest.SERVER,qq=RankingIndexTest.QQ;JSONArray charts=new JSONArray().put(RankingTest.chart(1,1,12,false).put("keys",6)).put(RankingTest.chart(2,2,13,true).put("keys",4));
        Files.writeString(cat,new JSONObject().put("server",server).put("charts",charts).toString());
        JSONArray scores=new JSONArray().put(RankingTest.score(1,1,99.8,3,100));byte[] raw=CloudRecords.pack(qq,server,scores);Path folder=Files.createDirectory(root.resolve(qq));Path archive=folder.resolve("scores-"+CloudRecords.sha256(raw)+".json");Files.write(archive,raw);
        String revision=RankingIndex.revision(server,charts);RankingIndexTest.Remote indices=new RankingIndexTest.Remote();
        RankingIndex.publish(indices,server,revision,0,qq,scores,charts,"Fixture",2);RankingIndex.publish(indices,server,revision,1,qq,scores,charts,"Fixture",2);
        for(Map.Entry<String,TreeMap<String,byte[]>> dir:indices.folders.entrySet())for(Map.Entry<String,byte[]> f:dir.getValue().entrySet()){Path out=root.resolve(dir.getKey().substring(1)).resolve(f.getKey());Files.createDirectories(out.getParent());Files.write(out,f.getValue());}
        Files.createDirectory(root.resolve("234567"));
        RankingDaemon d=new RankingDaemon(root,cat,state);check(d.refresh(true).getInt("verified")==1,"legacy parity verified");check(!Files.exists(root.resolve("_rankings/v2")),"verification publishes nothing");
        d.refresh(false);Path total=root.resolve(RankingSnapshot.path(revision,0).substring(1));RankingSnapshot board=RankingSnapshot.read(Files.readAllBytes(total),server,revision,0);
        check(board.players.size()==1&&Math.abs(board.players.get(0).rating-12.0/30)<1e-9,"server total equals legacy");
        Path single=root.resolve(RankingSnapshot.path(revision,1).substring(1));check(RankingSnapshot.read(Files.readAllBytes(single),server,revision,1).players.get(0).best.get(1).score==100,"single chart matches");
        Path empty=root.resolve(RankingSnapshot.path(revision,2).substring(1));check(RankingSnapshot.read(Files.readAllBytes(empty),server,revision,2).players.isEmpty(),"empty chart exists without legacy fallback");
        JSONObject active=new JSONObject(Files.readString(total.resolveSibling("activity.json"))).getJSONArray("players").getJSONObject(0);
        check(active.getInt("total")==1&&active.getInt("month")==0,"activity counts histories and excludes old records from month");
        JSONObject six=new JSONObject(Files.readString(total.resolveSibling("6k.json"))).getJSONArray("players").getJSONObject(0);
        check(Math.abs(six.getDouble("rating")-12.0/30)<1e-9&&six.getInt("oc")==1&&six.getInt("nc")==0,"6K independent O20 N10");
        check(!new JSONObject(Files.readString(total)).getJSONArray("players").getJSONObject(0).has("extra_boards"),"private timestamps not exposed in totals");
        check(Arrays.equals(raw,Files.readAllBytes(archive)),"original history byte-identical");
        JSONObject idle=d.refresh(false);check(idle.getInt("changed")==0&&idle.getInt("players")==1,"empty archives are cached without appearing on boards or triggering constant publication");
        Files.write(folder.resolve(PlayerTitle.FILE),PlayerTitle.pack(qq,server,"rank:0"));
        check(d.refresh(false).getInt("changed")==1,"title-only upload refreshes player snapshot without score uploads");
        RankingData.Player titled=RankingSnapshot.read(Files.readAllBytes(total),server,revision,0).players.get(0);
        check(titled.title.equals("小师妹")&&Math.abs(titled.rating-board.players.get(0).rating)<1e-12,"selected title does not change rating");
        JSONObject songTitle=new JSONObject(new String(PlayerTitle.pack(qq,server,"sid:1"),StandardCharsets.UTF_8));
        JSONArray unlocked=new JSONArray(scores.toString());unlocked.getJSONObject(0).put("accuracy_candidate",99.9);
        check(PlayerTitle.resolve(songTitle,qq,server,unlocked,charts,12).equals(charts.getJSONObject(0).getString("title")),"song title unlock is checked using real accuracy");
        check(PlayerTitle.resolve(songTitle,qq,server,scores,charts,12).equals("大师"),"locked song title falls back to actual rating title");
        check(PlayerTitle.resolve(songTitle,qq,"other",unlocked,charts,12).equals("大师"),"another server cannot provide a title");
        Files.write(archive,new byte[]{1});d=new RankingDaemon(root,cat,state);JSONObject status=d.refresh(false);check(status.getInt("failed")==1,"damaged source flagged");check(RankingSnapshot.read(Files.readAllBytes(total),server,revision,0).players.size()==1,"restart retains last good player");
        byte[] snapshot=Files.readAllBytes(total);try{RankingSnapshot.read(snapshot,server,revision,1);throw new AssertionError("wrong chart accepted");}catch(java.io.IOException expected){}
        JSONObject bad=new JSONObject(new String(snapshot,StandardCharsets.UTF_8));bad.getJSONArray("players").put(bad.getJSONArray("players").get(0));try{RankingSnapshot.read(bad.toString().getBytes(StandardCharsets.UTF_8),server,revision,0);throw new AssertionError("duplicate accepted");}catch(java.io.IOException expected){}
        try{d.refresh(true);throw new AssertionError("corrupt migration accepted");}catch(java.io.IOException expected){}
        System.out.println("PASS: server/legacy parity, totals, chart snapshots, no-write verification, original preservation, restart recovery and validation");
    }
}
