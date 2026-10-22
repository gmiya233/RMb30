import cn.gmiya.malodylink.*;
import org.json.*;
import java.nio.charset.StandardCharsets;

public final class CommunityDataTest {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static byte[] bytes(JSONObject doc){return doc.toString().getBytes(StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception {
        String server="https://fixture.invalid",revision=String.join("",java.util.Collections.nCopies(64,"a"));
        JSONObject review=CommunityData.review(server,"s1","12345","Fixture",5,"Test");
        for(int stars:new int[]{0,6})try{CommunityData.review(server,"s1","12345","Fixture",stars,"");throw new AssertionError("bad stars accepted");}catch(java.io.IOException expected){}
        try{CommunityData.review(server,"../s1","12345","Fixture",5,"");throw new AssertionError("bad path accepted");}catch(IllegalArgumentException expected){}
        JSONObject song=new JSONObject().put("schema",1).put("server",server).put("revision",revision).put("updated",System.currentTimeMillis()).put("topic","s1").put("average",5).put("downloads",20).put("rating_count",1).put("reviews",new JSONArray().put(new JSONObject(review.toString()).put("updated",1)));
        check(CommunityData.song(bytes(song),server,revision,"s1").getInt("rating_count")==1,"review snapshot accepted");
        check(CommunityData.chartDownloads(song,1)==-1,"old snapshot leaves download count unknown");
        song.put("chart_downloads",new JSONObject().put("1",100).put("2",0));
        JSONObject downloaded=CommunityData.song(bytes(song),server,revision,"s1");
        check(CommunityData.chartDownloads(downloaded,1)==100&&CommunityData.chartDownloads(downloaded,2)==0&&CommunityData.chartDownloads(downloaded,3)==-1,"per-chart counts distinguish zero and unavailable");
        song.getJSONObject("chart_downloads").put("2",-1);try{CommunityData.song(bytes(song),server,revision,"s1");throw new AssertionError("negative chart downloads accepted");}catch(java.io.IOException expected){}song.getJSONObject("chart_downloads").put("2",0);
        try{CommunityData.song(bytes(song),server,revision,"s2");throw new AssertionError("cross-song accepted");}catch(java.io.IOException expected){}
        song.getJSONArray("reviews").put(song.getJSONArray("reviews").get(0));try{CommunityData.song(bytes(song),server,revision,"s1");throw new AssertionError("duplicate votes accepted");}catch(java.io.IOException expected){}
        JSONObject rows=new JSONObject();for(String id:CommunityData.IDS)rows.put(id,new JSONArray());JSONObject boards=new JSONObject().put("schema",1).put("server",server).put("revision",revision).put("updated",System.currentTimeMillis()).put("boards",rows);
        check(CommunityData.boards(bytes(boards),server,revision).rows("song_good").length()==0,"unrated board is empty");
        rows.getJSONArray("song_hot").put(new JSONObject().put("id","s1").put("cid",1).put("name","Fixture").put("rank",1).put("downloads",42).put("average",4).put("rating_count",2));
        check(CommunityData.boards(bytes(boards),server,revision).rows("song_hot").length()==1,"bounded board accepted");
        JSONObject creator=new JSONObject().put("id",revision).put("name","Mapper").put("rank",1).put("downloads",42).put("average",0).put("rating_count",0);
        rows.put("creator_hot_all",new JSONArray().put(creator));
        check(CommunityData.boards(bytes(boards),server,revision).rows("creator_hot_all").length()==1,"optional expanded creator board accepted");
        creator.put("downloads",-1);try{CommunityData.boards(bytes(boards),server,revision);throw new AssertionError("invalid optional board accepted");}catch(java.io.IOException expected){}creator.put("downloads",42);
        rows.getJSONArray("song_hot").getJSONObject(0).put("average",6);try{CommunityData.boards(bytes(boards),server,revision);throw new AssertionError("invalid average accepted");}catch(java.io.IOException expected){}
        System.out.println("PASS: review validation, identity isolation, duplicate vote rejection and community board bounds");
    }
}
