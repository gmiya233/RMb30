import cn.gmiya.malodylink.*;
import org.json.*;
import java.util.*;

public final class CloudArchiveTest {
    static final String QQ="123456789",SERVER="https://fixture.invalid";
    static JSONArray catalogue(){return new JSONArray().put(new JSONObject().put("cid",1).put("server_hash","revision-1").put("title","Fixture").put("version","4KLV.12"));}
    static JSONObject score(int seed,int value){String hash=String.format(Locale.ROOT,"%032x",seed);return new JSONObject().put("id","1:"+(1700000000L+seed)+":"+hash).put("cid",1).put("server_hash","revision-1").put("chart_md5",hash).put("played_at",1700000000L+seed).put("score_candidate",value).put("accuracy_candidate",99.5).put("player","SYNTHETIC TEST").put("raw_score_fields",new JSONObject().put("4",3).put("5",value));}
    static final class Remote implements CloudArchive.Remote {
        final Map<String,TreeMap<String,byte[]>> folders=new HashMap<>();int writes,creates;Runnable afterWrite;
        public List<String> list(String q,boolean create){if(create&&!folders.containsKey(q)){folders.put(q,new TreeMap<>());creates++;}return new ArrayList<>(folders.getOrDefault(q,new TreeMap<>()).keySet());}
        public byte[] get(String q,String name){return folders.get(q).get(name).clone();}
        public void put(String q,String name,byte[] bytes){folders.computeIfAbsent(q,k->new TreeMap<>()).put(name,bytes.clone());writes++;if(afterWrite!=null){Runnable r=afterWrite;afterWrite=null;r.run();}}
        void seed(String qq,String server,JSONObject score)throws Exception{byte[] data=CloudRecords.pack(qq,server,new JSONArray().put(score));folders.computeIfAbsent(qq,k->new TreeMap<>()).put("scores-"+CloudRecords.sha256(data)+".json",data);}
    }
    static CloudArchive.Result sync(Remote r,JSONArray local,boolean upload)throws Exception{return CloudArchive.sync(r,QQ,SERVER,local,catalogue(),upload,p->{});}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);System.out.println("PASS: "+message);}
    public static void main(String[] args)throws Exception {
        Remote remote=new Remote();JSONArray a=new JSONArray().put(score(1,100));String original=a.toString();
        CloudArchive.Result one=sync(remote,a,true);check(one.uploaded==1&&one.merged.scores.length()==1&&remote.creates==1,"first merge creates one archive and uploads score batch");
        CloudArchive.Result two=sync(remote,new JSONArray().put(score(2,200)),true);check(two.uploaded==1&&two.downloaded==1&&two.merged.scores.length()==2,"second phone merges both histories without replacing first batch");
        int before=remote.writes;CloudArchive.Result pull=sync(remote,new JSONArray(),false);check(pull.downloaded==2&&remote.writes==before,"empty phone restores both scores with pull-only operation");
        check(sync(remote,pull.merged.scores,true).uploaded==0&&remote.writes==before,"repeated merge is idempotent and uploads no duplicates");
        check(a.toString().equals(original),"sync never modifies input score objects");
        Remote none=new Remote();check(sync(none,new JSONArray(),false).merged.scores.length()==0&&none.creates==0,"pull from nonexistent archive does not create folders");
        Remote conflict=new Remote();conflict.seed(QQ,SERVER,score(1,900));CloudArchive.Result merged=sync(conflict,a,true);
        check(merged.merged.conflicts==1&&merged.merged.scores.getJSONObject(0).getInt("score_candidate")==100,"same-ID conflict preserves local raw score");
        check(conflict.folders.get(QQ).size()==2,"both conflicting cloud originals remain recoverable");
        Remote concurrent=new Remote();concurrent.afterWrite=()->{try{concurrent.seed(QQ,SERVER,score(3,300));}catch(Exception e){throw new RuntimeException(e);}};
        check(sync(concurrent,a,true).merged.scores.length()==2,"second listing picks up concurrent upload without lost updates");
        Remote bad=new Remote();bad.seed(QQ,SERVER,score(1,100));String name=bad.folders.get(QQ).firstKey();bad.folders.get(QQ).put(name,new byte[]{1,2,3});boolean failed=false;
        try{sync(bad,a,true);}catch(java.io.IOException expected){failed=true;}check(failed&&bad.writes==0&&a.toString().equals(original),"corrupt cloud file stops sync before writes and preserves local input");
        JSONArray incoming=new JSONArray().put(score(1,100).put("server_hash","removed-version")).put(score(2,100).put("cid",999)).put(score(3,100).put("accuracy_candidate",200));
        CloudRecords.Merged filtered=CloudRecords.merge(new JSONArray(),incoming,new CloudRecords.Catalogue(catalogue()));check(filtered.scores.length()==0&&filtered.skipped==3,"stale catalogue versions, unknown charts and invalid accuracy excluded");
        for(String invalid:new String[]{"../12345","0012345","1234","12345/x"}){boolean rejected=false;try{CloudRecords.qq(invalid);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"QQ path rejected: "+invalid);}
        byte[] other=CloudRecords.pack("987654321",SERVER,a);failed=false;try{CloudRecords.unpack(other,QQ,SERVER);}catch(java.io.IOException expected){failed=true;}check(failed,"wrong QQ envelope is rejected");
        check(CloudRecords.unpack(CloudRecords.pack(QQ,"https://other.invalid",a),QQ,SERVER).length()==0,"other catalogue server is not imported");
    }
}
