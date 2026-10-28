package cn.gmiya.malodylink;

import org.json.*;
import java.io.IOException;
import java.util.*;

/** Validate the dedicated preview namespace before reading any image file. */
public final class ChartPreviewData {
    public static final class Page {public String image,digest;public int bytes,width,height;}
    public final List<Page> pages=new ArrayList<>();public final int keys,notes;
    public ChartPreviewData(JSONObject doc,String server,JSONObject chart)throws Exception {
        if(doc.optInt("schema")!=1||!server.equals(doc.optString("server"))||doc.optInt("cid")!=chart.optInt("cid")||!chart.optString("server_hash").equals(doc.optString("server_hash")))throw new IOException("预览与当前谱面不匹配，请更新谱库后重试");
        keys=doc.optInt("keys");notes=doc.optInt("notes");JSONArray rows=doc.optJSONArray("pages");
        if(!"ready".equals(doc.optString("status"))||keys<4||keys>6||notes<1||notes>40000||rows==null||rows.length()<1||rows.length()>4)throw new IOException("谱面预览暂未就绪，目前支持4K／5K／6K直轨");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);Page p=new Page();p.image=row.getString("image");p.digest=row.getString("sha256");p.bytes=row.getInt("bytes");p.width=row.getInt("width");p.height=row.getInt("height");
            if(!p.digest.matches("[a-f0-9]{64}")||!p.image.equals(chart.optInt("cid")+"-"+p.digest+".png")||p.bytes<1||p.bytes>4*1024*1024||p.width<1||p.width>8192||p.height<1||p.height>4096||(long)p.width*p.height>16000000)throw new IOException("预览图片信息无效");pages.add(p);}
    }
    public static void verify(Page page,byte[] bytes)throws Exception {
        byte[] png={(byte)137,80,78,71,13,10,26,10};
        if(bytes.length!=page.bytes||bytes.length<8||!Arrays.equals(Arrays.copyOf(bytes,8),png)||!CloudRecords.sha256(bytes).equals(page.digest))throw new IOException("预览图片校验失败，请刷新重试");
    }
}
