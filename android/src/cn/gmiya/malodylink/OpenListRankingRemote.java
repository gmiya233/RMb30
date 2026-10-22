package cn.gmiya.malodylink;

import org.json.*;
import java.io.*;
import java.util.*;

/** All mutations are confined to derived /RMB30/_rankings/v1 indices. */
public final class OpenListRankingRemote implements RankingIndex.Remote {
    private final OpenListScoreRemote source;
    public OpenListRankingRemote(OpenListScoreRemote source){this.source=source;}
    private static void path(String path,boolean folder){String base="/_rankings/v1/[a-f0-9]{64}/(?:rt|charts/[1-9][0-9]*)";if(!path.matches(base+(folder?"/[1-9][0-9]{4,11}":"(?:/[1-9][0-9]{4,11})?")))throw new IllegalArgumentException("仅允许排行索引路径");}
    private List<String> list(String path,boolean directories)throws Exception {path(path,!directories);List<String> result=new ArrayList<>();for(int page=1;page<=100;page++){JSONObject r=source.api("/api/fs/list",new JSONObject().put("path",path).put("page",page).put("per_page",100).put("refresh",true));if(page==1&&r.optInt("code")!=200){String message=r.optString("message").toLowerCase(Locale.ROOT);if(message.contains("object not found")||message.contains("no such file"))return result;}OpenListScoreRemote.ok(r);JSONObject data=r.getJSONObject("data");JSONArray rows=data.optJSONArray("content");if(rows==null)return result;for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String name=row.optString("name");if(row.optBoolean("is_dir")==directories&&(directories?name.matches("[1-9][0-9]{4,11}"):RankingIndex.name(name))){if(!directories&&row.optLong("size")>RankingIndex.MAX_BYTES)throw new IOException("排行摘要过大");result.add(name);}}if(result.size()>(directories?1000:RankingIndex.MAX_HEADS))throw new IOException("排行索引数量超过上限");if(rows.length()<100||page*100>=data.optInt("total",Integer.MAX_VALUE)){Collections.sort(result);return result;}}throw new IOException("排行索引目录页数超过上限");}
    public List<String> directories(String path)throws Exception{return list(path,true);}
    public List<String> files(String path)throws Exception{return list(path,false);}
    public byte[] get(String path,String name)throws Exception {path(path,true);if(!RankingIndex.name(name))throw new IllegalArgumentException("无效排行文件名");return source.getPath(path+"/"+name,RankingIndex.MAX_BYTES);}
    public void put(String path,String name,byte[] bytes)throws Exception {path(path,true);if(bytes.length>RankingIndex.MAX_BYTES||!name.equals("entry-"+CloudRecords.sha256(bytes)+".json"))throw new IOException("排行摘要校验失败");OpenListScoreRemote.ok(source.api("/api/fs/mkdir",new JSONObject().put("path",path)));Map<String,String> headers=new HashMap<>();headers.put("Content-Type","application/json");headers.put("File-Path",path+"/"+name);headers.put("As-Task","false");OpenListScoreRemote.ok(source.readJson(server()+"/api/fs/put","PUT",bytes,headers,true,100000));}
    private String server(){return source.baseAddress();}
    public void remove(String path,List<String> observedNames)throws Exception {path(path,true);if(observedNames.size()>RankingIndex.MAX_HEADS)throw new IllegalArgumentException("索引删除数量过多");JSONArray names=new JSONArray();for(String name:observedNames){if(!RankingIndex.name(name))throw new IllegalArgumentException("仅允许清理已合并的排行摘要");names.put(name);}if(names.length()>0)OpenListScoreRemote.ok(source.api("/api/fs/remove",new JSONObject().put("dir",path).put("names",names)));}
}
