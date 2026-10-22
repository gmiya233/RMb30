import cn.gmiya.malodylink.*;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class RankingIndexTest {
    static int checks;
    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;}
    static boolean near(double a,double b){return Math.abs(a-b)<1e-9;}
    static final String SERVER="https://fixture.invalid",QQ="123456";
    static final class Remote implements RankingIndex.Remote {
        final Map<String,TreeMap<String,byte[]>> folders=new HashMap<>();int writes,removes;boolean fail;Runnable afterWrite;
        public List<String> directories(String p){List<String> ids=new ArrayList<>();for(String path:folders.keySet())if(path.startsWith(p+"/")&&!path.substring(p.length()+1).contains("/"))ids.add(path.substring(p.length()+1));return ids;}
        public List<String> files(String p){return new ArrayList<>(folders.getOrDefault(p,new TreeMap<>()).keySet());}
        public byte[] get(String p,String n)throws Exception {byte[] b=folders.getOrDefault(p,new TreeMap<>()).get(n);if(b==null)throw new java.io.IOException("gone");return b.clone();}
        public void put(String p,String n,byte[] b)throws Exception{if(fail)throw new java.io.IOException("offline");folders.computeIfAbsent(p,k->new TreeMap<>()).put(n,b.clone());writes++;if(afterWrite!=null){Runnable callback=afterWrite;afterWrite=null;callback.run();}}
        public void remove(String p,List<String> n){for(String name:n){folders.get(p).remove(name);removes++;}}
        void seed(String path,JSONObject doc)throws Exception {byte[] bytes=ChartMatcher.canonical(doc).getBytes(StandardCharsets.UTF_8);folders.computeIfAbsent(path,k->new TreeMap<>()).put("entry-"+CloudRecords.sha256(bytes)+".json",bytes);}
    }
    public static void main(String[] args)throws Exception {
        JSONArray charts=new JSONArray().put(RankingTest.chart(1,1,12,false)).put(RankingTest.chart(2,2,13,true));String revision=RankingIndex.revision(SERVER,charts),path=RankingIndex.folder(revision,0,QQ);
        JSONArray a=new JSONArray().put(RankingTest.score(1,1,99.5,3,100)),b=new JSONArray().put(RankingTest.score(2,2,99.8,3,200));Remote r=new Remote();
        RankingIndex.publish(r,SERVER,revision,0,QQ,a,charts,"Fixture",2);check(r.files(path).size()==1,"first index");int before=r.writes;RankingIndex.publish(r,SERVER,revision,0,QQ,a,charts,"Fixture",2);check(r.writes==before,"idempotent publication");
        r.afterWrite=()->{try{r.seed(path,RankingIndex.document(SERVER,revision,0,QQ,a,"Fixture",2));}catch(Exception e){throw new RuntimeException(e);}};
        RankingIndex.publish(r,SERVER,revision,0,QQ,b,charts,"Fixture",3);check(near(RankingIndex.player(QQ,RankingIndex.read(r,SERVER,revision,0,QQ,charts),charts).rating,(12+13*1.01)/30),"upload unions previous summary");
        Remote racing=new Remote();racing.afterWrite=()->{try{racing.seed(path,RankingIndex.document(SERVER,revision,0,QQ,b,"Fixture",3));}catch(Exception e){throw new RuntimeException(e);}};RankingIndex.publish(racing,SERVER,revision,0,QQ,a,charts,"Fixture",2);check(racing.files(path).size()==2&&RankingIndex.read(racing,SERVER,revision,0,QQ,charts).scores.length()==2,"publication never deletes concurrent unseen head");
        Remote concurrent=new Remote();concurrent.seed(path,RankingIndex.document(SERVER,revision,0,QQ,a,"Fixture",2));concurrent.seed(path,RankingIndex.document(SERVER,revision,0,QQ,b,"Fixture",3));check(RankingIndex.read(concurrent,SERVER,revision,0,QQ,charts).scores.length()==2,"concurrent unseen heads both survive");
        RankingIndex.publish(concurrent,SERVER,revision,0,QQ,a,charts,"Fixture",2);check(concurrent.files(path).size()==1&&concurrent.removes==2,"subsequent publish compacts only incorporated heads");
        concurrent.fail=true;try{RankingIndex.publish(concurrent,SERVER,revision,0,QQ,new JSONArray().put(RankingTest.score(1,5,99.9,3,999)),charts,"Fixture",5);throw new AssertionError("failure expected");}catch(java.io.IOException expected){}check(concurrent.files(path).size()==1,"failed publication never deletes last good head");
        String single=RankingIndex.folder(revision,1,QQ);RankingIndex.publish(r,SERVER,revision,1,QQ,a,charts,"Fixture",2);check(RankingIndex.read(r,SERVER,revision,1,QQ,charts).scores.length()==1,"single chart index separated");check(r.files(single).size()==1,"chart stored in own folder");
        check(!RankingIndex.revision(SERVER,new JSONArray().put(RankingTest.chart(1,1,13,false))).equals(revision),"rating-affecting catalogue revision isolated");
        JSONArray descriptive=new JSONArray(charts.toString());descriptive.getJSONObject(0).put("bpm",999).put("cover","new-cover");check(RankingIndex.revision(SERVER,descriptive).equals(revision),"cosmetic metadata does not invalidate indices");
        JSONArray manyCharts=new JSONArray(),left=new JSONArray(),right=new JSONArray(),all=new JSONArray();for(int i=1;i<=80;i++){manyCharts.put(RankingTest.chart(i,i,10+i%5,i>50));JSONObject s=RankingTest.score(i,i,99.0+i%10*.1,3,100+i);all.put(s);(i%2==0?left:right).put(s);}JSONArray union=new JSONArray();for(JSONArray part:new JSONArray[]{RankingData.shortlist(QQ,left,manyCharts),RankingData.shortlist(QQ,right,manyCharts)})for(int i=0;i<part.length();i++)union.put(part.get(i));RankingData.Player merged=RankingData.player(QQ,union,manyCharts),full=RankingData.player(QQ,all,manyCharts);
        check(near(merged.rating,full.rating)&&near(merged.oldRating,full.oldRating)&&near(merged.newRating,full.newRating),"union of concurrent top-K summaries equals complete archive ranking");check(RankingData.shortlist(QQ,all,manyCharts).length()==30,"RT index bounded to O20 N10 rather than history");
        JSONArray promoted=new JSONArray().put(RankingTest.chart(1,1,12,false)).put(RankingTest.chart(2,1,13,true));check(RankingData.player(QQ,a,promoted).newCount==1,"song category comes from catalogue independent of uploaded difficulties");
        Remote corrupt=new Remote();corrupt.folders.computeIfAbsent(path,k->new TreeMap<>()).put("entry-"+String.join("",Collections.nCopies(64,"0"))+".json",new byte[]{1});try{RankingIndex.read(corrupt,SERVER,revision,0,QQ,charts);throw new AssertionError("checksum expected");}catch(java.io.IOException expected){checks++;}
        System.out.println("PASS: "+checks+" immutable index, concurrent merge, bounded summary and failure checks");
    }
}
