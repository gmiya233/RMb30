package cn.gmiya.malodylink;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Rank1 is confined to /RMB30. OpenList API paths are relative to this account's base_path. */
public final class OpenListScoreRemote implements CloudArchive.Remote {
    private final String base;private String token="";
    public OpenListScoreRemote(String address,String user,String password,String expectedRoot)throws Exception {
        base=address.replaceAll("/+$","");URL url=new URL(base);if((!url.getProtocol().equals("http")&&!url.getProtocol().equals("https"))||url.getUserInfo()!=null||url.getQuery()!=null||url.getRef()!=null)throw new IOException("云端地址无效");
        JSONObject login=api("/api/auth/login",new JSONObject().put("username",user).put("password",password));ok(login);token=login.getJSONObject("data").getString("token");
        JSONObject me=readJson(base+"/api/me","GET",null,null,true,100000);ok(me);String root=me.getJSONObject("data").optString("base_path").replaceAll("/+$","");
        if(!expectedRoot.equals(root)||!"/RMB30".equals(root))throw new IOException("成绩账号的根目录不是RMB30，已停止同步");
    }
    private static void ok(JSONObject r)throws IOException {if(r.optInt("code")!=200)throw new IOException("云端请求失败（服务代码 "+r.optInt("code")+"）");}
    private byte[] http(String address,String method,byte[] bytes,Map<String,String> headers,boolean auth,int limit)throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(address).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setRequestMethod(method);
        if(auth&&!token.isEmpty())c.setRequestProperty("Authorization",token);if(headers!=null)for(Map.Entry<String,String> h:headers.entrySet())c.setRequestProperty(h.getKey(),h.getValue());
        try{if(bytes!=null){c.setDoOutput(true);c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
            int code=c.getResponseCode();if(code!=200)throw new IOException("云端连接失败（HTTP "+code+"）");return CloudRecords.read(c.getInputStream(),limit);
        }finally{c.disconnect();}
    }
    private JSONObject readJson(String address,String method,byte[] data,Map<String,String> headers,boolean auth,int limit)throws Exception {return new JSONObject(new String(http(address,method,data,headers,auth,limit),StandardCharsets.UTF_8));}
    private JSONObject api(String path,JSONObject data)throws Exception {Map<String,String> headers=new HashMap<>();headers.put("Content-Type","application/json; charset=utf-8");return readJson(base+path,"POST",data.toString().getBytes(StandardCharsets.UTF_8),headers,true,2000000);}
    @Override public List<String> list(String value,boolean create)throws Exception {
        String path="/"+CloudRecords.qq(value);if(create)ok(api("/api/fs/mkdir",new JSONObject().put("path",path)));
        List<String> names=new ArrayList<>();int page=1;for(;;){JSONObject r=api("/api/fs/list",new JSONObject().put("path",path).put("password","").put("page",page).put("per_page",100).put("refresh",true));
            if(!create&&page==1&&r.optInt("code")!=200){String message=r.optString("message").toLowerCase(Locale.ROOT);if(message.contains("object not found")||message.contains("no such file"))return names;}
            ok(r);JSONObject data=r.getJSONObject("data");JSONArray content=data.optJSONArray("content");if(content==null)break;
            for(int i=0;i<content.length();i++){JSONObject item=content.getJSONObject(i);String name=item.optString("name");if(!item.optBoolean("is_dir")&&CloudArchive.fileName(name)){if(item.optLong("size")>CloudRecords.MAX_BYTES)throw new IOException("云端分片过大");names.add(name);}}
            if(names.size()>1000||page>100)throw new IOException("云端文件数量超过上限");if(content.length()<100||page*100>=data.optInt("total",Integer.MAX_VALUE))break;page++;
        }Collections.sort(names);return names;
    }
    private static String path(String qq,String name){if(!CloudArchive.fileName(name))throw new IllegalArgumentException("无效成绩文件名");return "/"+CloudRecords.qq(qq)+"/"+name;}
    @Override public byte[] get(String qq,String name)throws Exception {
        JSONObject r=api("/api/fs/get",new JSONObject().put("path",path(qq,name)).put("password",""));ok(r);URL url=new URL(r.getJSONObject("data").getString("raw_url")),origin=new URL(base);
        if((!url.getProtocol().equals("http")&&!url.getProtocol().equals("https"))||url.getUserInfo()!=null)throw new IOException("云端文件地址无效");
        boolean same=origin.getProtocol().equals(url.getProtocol())&&origin.getHost().equals(url.getHost())&&origin.getPort()==url.getPort();return http(url.toString(),"GET",null,null,same,CloudRecords.MAX_BYTES);
    }
    @Override public void put(String qq,String name,byte[] bytes)throws Exception {
        if(bytes.length>CloudRecords.MAX_BYTES||!name.equals("scores-"+CloudRecords.sha256(bytes)+".json"))throw new IOException("待上传成绩文件校验失败");
        Map<String,String> headers=new HashMap<>();headers.put("Content-Type","application/octet-stream");headers.put("File-Path",path(qq,name));headers.put("As-Task","false");ok(readJson(base+"/api/fs/put","PUT",bytes,headers,true,100000));
    }
}
