package cn.gmiya.malodylink;

import org.json.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;

/** Server-side read-only history reader. Only derived v2 snapshots and private state are written. */
public final class RankingDaemon {
    private final Path root,catalogue,state;
    private final Map<String,RankingData.Player> previous=new TreeMap<>();
    private final Map<String,String> signatures=new HashMap<>();
    private String loadedRevision="";
    private long published;
    public RankingDaemon(Path root,Path catalogue,Path state)throws Exception {
        this.root=root.toRealPath();this.catalogue=catalogue;this.state=state;Files.createDirectories(state);
        if(state.toAbsolutePath().normalize().startsWith(this.root))throw new IOException("Private state must be outside the archive root");
    }
    private static byte[] read(Path path,int limit)throws Exception {
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid source file");
        return CloudRecords.read(Files.newInputStream(path),limit);
    }
    private static List<String> children(Path dir,boolean directories)throws Exception {
        List<String> result=new ArrayList<>();if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS))return result;
        if(Files.isSymbolicLink(dir)||!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid source directory");
        try(DirectoryStream<Path> paths=Files.newDirectoryStream(dir)){for(Path p:paths){
            if(Files.isSymbolicLink(p))throw new IOException("Symlinks are not accepted in the archive");
            if(directories==Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))result.add(p.getFileName().toString());
        }}Collections.sort(result);return result;
    }
    private CloudArchive.Remote history(){return new CloudArchive.Remote(){
        public List<String> list(String qq,boolean create)throws Exception {if(create)throw new IOException("Read only");List<String> names=new ArrayList<>();for(String n:children(root.resolve(CloudRecords.qq(qq)),false))if(CloudArchive.fileName(n))names.add(n);return names;}
        public byte[] get(String qq,String name)throws Exception {if(!CloudArchive.fileName(name))throw new IOException("Invalid name");return read(root.resolve(CloudRecords.qq(qq)).resolve(name),CloudRecords.MAX_BYTES);}
        public void put(String q,String n,byte[] b)throws Exception {throw new IOException("Read only");}
    };}
    private RankingIndex.Remote legacy(){return new RankingIndex.Remote(){
        private Path resolve(String p)throws Exception {
            if(!p.matches("/_rankings/v1/[a-f0-9]{64}/(?:rt|charts/[1-9][0-9]*)(?:/[1-9][0-9]{4,11})?"))throw new IOException("Invalid index path");
            Path path=root.resolve(p.substring(1));if(!path.toRealPath().startsWith(root))throw new IOException("Outside root");return path;
        }
        public List<String> directories(String p)throws Exception{return children(resolve(p),true);}
        public List<String> files(String p)throws Exception {Path path=root.resolve(p.substring(1));if(!Files.exists(path))return new ArrayList<>();return children(resolve(p),false);}
        public byte[] get(String p,String n)throws Exception{if(!RankingIndex.name(n))throw new IOException("Invalid index name");return read(resolve(p).resolve(n),RankingIndex.MAX_BYTES);}
        public void put(String p,String n,byte[] b)throws Exception{throw new IOException("Read only");}
        public void remove(String p,List<String> n)throws Exception{throw new IOException("Read only");}
    };}
    private String signature(String qq,List<String> names)throws Exception {
        List<String> tracked=new ArrayList<>(names);if(Files.exists(root.resolve(qq).resolve(PlayerTitle.FILE),LinkOption.NOFOLLOW_LINKS))tracked.add(PlayerTitle.FILE);
        StringBuilder value=new StringBuilder();for(String n:tracked){Path p=root.resolve(qq).resolve(n);BasicFileAttributes a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);value.append(n).append(':').append(a.size()).append(':').append(a.lastModifiedTime()).append('\n');}
        return CloudRecords.sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void atomic(Path path,JSONObject json)throws Exception {
        byte[] bytes=json.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>16*1024*1024)throw new IOException("Snapshot exceeds 16 MiB");
        Files.createDirectories(path.getParent());Path temporary=path.resolveSibling(path.getFileName()+".tmp");
        Files.write(temporary,bytes);Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    public JSONObject refresh(boolean verify)throws Exception {
        JSONObject doc=new JSONObject(new String(read(catalogue,16*1024*1024),StandardCharsets.UTF_8));
        String server=doc.getString("server");JSONArray charts=doc.getJSONArray("charts");String revision=RankingIndex.revision(server,charts);
        if(!revision.equals(loadedRevision)){
            previous.clear();signatures.clear();loadedRevision=revision;Path saved=state.resolve(revision+".json");
            if(Files.exists(saved)){JSONObject old=new JSONObject(new String(read(saved,16*1024*1024),StandardCharsets.UTF_8));JSONArray rows=old.getJSONArray("players");for(int i=0;i<rows.length();i++){RankingData.Player p=RankingData.decode(rows.getJSONObject(i));previous.put(p.qq,p);}}
        }
        CloudArchive.Remote source=history();Set<String> ids=new TreeSet<>();for(String id:children(root,true))if(id.matches("[1-9][0-9]{4,11}"))ids.add(id);
        ids.addAll(previous.keySet());int changed=0,failed=0,verified=0;Map<String,RankingData.Player> next=new TreeMap<>();
        if(verify){
            Path oldTotals=root.resolve(RankingIndex.board(revision,0).substring(1));
            for(String qq:children(oldTotals,true))if(qq.matches("[1-9][0-9]{4,11}")&&!ids.contains(qq))throw new IOException("A legacy player has no source archive; migration stopped");
        }
        for(String qq:ids){try{
            Path folder=root.resolve(qq);if(!Files.isDirectory(folder))throw new IOException("Archive unavailable");
            List<String> names=source.list(qq,false);String stamp=signature(qq,names);RankingData.Player player=previous.get(qq);
            if(player==null||!stamp.equals(signatures.get(qq))){
                CloudArchive.Result merged=CloudArchive.sync(source,qq,server,new JSONArray(),charts,false,p->{});
                if(!names.equals(source.list(qq,false))||!stamp.equals(signature(qq,names)))throw new IOException("Source changed during indexing");
                if(merged.merged.conflicts>0)throw new IOException("Conflicting history records require administrator review");
                player=RankingData.player(qq,merged.merged.scores,charts);
                JSONObject appearance=null;try{Path title=root.resolve(qq).resolve(PlayerTitle.FILE);if(Files.exists(title,LinkOption.NOFOLLOW_LINKS))appearance=new JSONObject(new String(read(title,4096),StandardCharsets.UTF_8));}catch(Exception invalidTitle){}
                player.title=PlayerTitle.resolve(appearance,qq,server,merged.merged.scores,charts,player.rating);changed++;
            }
            if(verify){
                RankingIndex.Heads heads=RankingIndex.read(legacy(),server,revision,0,qq,charts);
                if(!heads.names.isEmpty()){
                    RankingData.Player old=RankingIndex.player(qq,heads,charts);
                    if(Math.abs(old.rating-player.rating)>1e-9||old.oldCount!=player.oldCount||old.newCount!=player.newCount)throw new IOException("Legacy total differs");
                    for(int i=0;i<charts.length();i++){
                        int cid=charts.getJSONObject(i).getInt("cid");RankingIndex.Heads single=RankingIndex.read(legacy(),server,revision,cid,qq,charts);
                        if(single.names.isEmpty())continue;RankingData.Score score=player.best.get(cid),oldScore=RankingIndex.player(qq,single,charts).best.get(cid);
                        if(score==null)throw new IOException("Legacy chart has no source record");
                        if(oldScore==null||Double.compare(score.rating,oldScore.rating)!=0||Double.compare(score.accuracy,oldScore.accuracy)!=0||score.score!=oldScore.score)throw new IOException("Legacy chart differs");
                    }verified++;
                }
            }
            next.put(qq,player);signatures.put(qq,stamp);
        }catch(Exception unavailable){failed++;if(previous.containsKey(qq))next.put(qq,previous.get(qq));}}
        if(verify&&failed>0)throw new IOException("Migration verification failed for "+failed+" archives; no snapshots published");
        if(!ids.isEmpty()&&next.isEmpty())throw new IOException("No readable archives; previous snapshots retained");
        int ranked=0;for(RankingData.Player player:next.values())if(!player.best.isEmpty())ranked++;
        JSONObject status=new JSONObject().put("schema",2).put("revision",revision).put("players",ranked).put("failed",failed).put("changed",changed).put("verified",verified);
        if(verify)return status; // Verification never publishes or alters migration state.
        boolean same=changed==0&&failed==0&&next.size()==previous.size()&&System.currentTimeMillis()-published<60000;Path output=root.resolve("_rankings/v2/"+revision);
        if(!same||!Files.exists(output.resolve("rt.json"))){
            long updated=System.currentTimeMillis();JSONArray all=new JSONArray(),totals=new JSONArray();Map<Integer,JSONArray> singles=new TreeMap<>();
            for(int i=0;i<charts.length();i++)singles.put(charts.getJSONObject(i).getInt("cid"),new JSONArray());
            for(RankingData.Player player:next.values()){
                JSONObject encoded=RankingData.encode(player);all.put(encoded);
                // Remember empty archives privately, but do not rank them or re-read them every poll.
                if(player.best.isEmpty())continue;
                totals.put(new JSONObject(encoded.toString()).put("best",new JSONArray()));
                JSONArray best=encoded.getJSONArray("best");for(int i=0;i<best.length();i++){JSONObject score=best.getJSONObject(i);int cid=score.getInt("cid");
                    JSONArray board=singles.computeIfAbsent(cid,k->new JSONArray());board.put(new JSONObject(encoded.toString()).put("best",new JSONArray().put(score)));
                }
            }
            for(Map.Entry<Integer,JSONArray> entry:singles.entrySet())atomic(output.resolve("charts/"+entry.getKey()+".json"),snapshot(server,revision,entry.getKey(),entry.getValue(),updated,failed,ids.size()));
            // The total snapshot is the generation's final publication marker.
            atomic(output.resolve("rt.json"),snapshot(server,revision,0,totals,updated,failed,ids.size()));
            atomic(state.resolve(revision+".json"),new JSONObject().put("players",all));
            published=updated;
        }
        previous.clear();previous.putAll(next);
        atomic(root.resolve("_rankings/v2/status.json"),status);return status;
    }
    private static JSONObject snapshot(String server,String revision,int cid,JSONArray players,long updated,int failed,int folders){
        return new JSONObject().put("schema",2).put("server",server).put("revision",revision).put("cid",cid).put("updated",updated).put("failed",failed).put("folders",folders).put("players",players);
    }
    public static void main(String[] args)throws Exception {
        if(args.length<3)throw new IllegalArgumentException("Usage: archive-root catalogue-json private-state [--verify|--once]");
        RankingDaemon daemon=new RankingDaemon(Paths.get(args[0]),Paths.get(args[1]),Paths.get(args[2]));
        if(args.length>3){System.out.println(daemon.refresh(args[3].equals("--verify")));return;}
        for(;;){try{JSONObject result=daemon.refresh(false);if(result.optInt("changed")>0||result.optInt("failed")>0)System.out.println("players="+result.optInt("players")+" changed="+result.optInt("changed")+" failed="+result.optInt("failed"));}
            catch(Exception failure){System.err.println("Ranking update failed; snapshots retained ("+failure.getClass().getSimpleName()+")");}
            Thread.sleep(5000);
        }
    }
}
