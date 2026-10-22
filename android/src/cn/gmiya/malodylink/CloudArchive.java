package cn.gmiya.malodylink;

import org.json.*;
import java.io.*;
import java.util.*;

/** Append-only content-addressed batches avoid lost updates when multiple phones upload together. */
public final class CloudArchive {
    public interface Remote {List<String> list(String qq,boolean create)throws Exception;byte[] get(String qq,String name)throws Exception;void put(String qq,String name,byte[] bytes)throws Exception;}
    public interface Progress {void update(String message);}
    public static final class Result {public CloudRecords.Merged merged;public int uploaded,downloaded;}
    public static boolean fileName(String name){return name!=null&&name.matches("scores-[a-f0-9]{64}\\.json");}
    private static void collect(Remote remote,String qq,String server,List<String> names,Set<String> seen,JSONArray rows,Progress progress)throws Exception {
        if(names.size()>1000)throw new IOException("云端分片超过1000个，本次保留本地数据");
        for(String name:names){if(!fileName(name)||!seen.add(name))continue;progress.update("拉取云端成绩 "+seen.size()+" / "+names.size());
            byte[] bytes=remote.get(qq,name);if(!name.equals("scores-"+CloudRecords.sha256(bytes)+".json"))throw new IOException("云端文件校验失败，请稍后重试");
            JSONArray batch=CloudRecords.unpack(bytes,qq,server);if(rows.length()+batch.length()>CloudRecords.MAX_RECORDS)throw new IOException("云端记录超过读取上限，本地数据保留");for(int i=0;i<batch.length();i++)rows.put(batch.get(i));
        }
    }
    public static Result sync(Remote remote,String value,String server,JSONArray local,JSONArray charts,boolean upload,Progress progress)throws Exception {
        String qq=CloudRecords.qq(value);CloudRecords.Catalogue catalogue=new CloudRecords.Catalogue(charts);JSONArray cloud=new JSONArray();Set<String> seen=new HashSet<>();
        collect(remote,qq,server,remote.list(qq,upload),seen,cloud,progress);Result result=new Result();result.merged=CloudRecords.merge(local,cloud,catalogue);
        if(upload){Set<String> present=new HashSet<>();for(int i=0;i<cloud.length();i++){JSONObject row=catalogue.clean(cloud.optJSONObject(i));if(row!=null)present.add(CloudRecords.fingerprint(row));}
            JSONArray batch=new JSONArray();for(int i=0;i<result.merged.scores.length();i++){JSONObject row=result.merged.scores.getJSONObject(i);if(present.contains(CloudRecords.fingerprint(row)))continue;batch.put(row);if(batch.length()==CloudRecords.MAX_BATCH){upload(remote,qq,server,batch,seen,progress);result.uploaded+=batch.length();batch=new JSONArray();}}
            if(batch.length()>0){upload(remote,qq,server,batch,seen,progress);result.uploaded+=batch.length();}
            // Pick up batches another phone finished while this phone was uploading. Later arrivals are picked up next time.
            collect(remote,qq,server,remote.list(qq,false),seen,cloud,progress);result.merged=CloudRecords.merge(local,cloud,catalogue);
            // This phone's newly created batches were marked seen and are already represented in local.
        }
        result.downloaded=result.merged.added;return result;
    }
    private static void upload(Remote remote,String qq,String server,JSONArray rows,Set<String> seen,Progress progress)throws Exception {
        byte[] bytes=CloudRecords.pack(qq,server,rows);String name="scores-"+CloudRecords.sha256(bytes)+".json";progress.update("上传合并成绩（"+rows.length()+"条）…");remote.put(qq,name,bytes);
        if(!Arrays.equals(bytes,remote.get(qq,name)))throw new IOException("云端回读校验未通过，本地数据保留");seen.add(name);
    }
}
