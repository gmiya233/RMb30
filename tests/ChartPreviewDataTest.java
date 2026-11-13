import cn.gmiya.malodylink.*;
import org.json.*;
import java.nio.charset.StandardCharsets;

public class ChartPreviewDataTest {
    public static void main(String[] args)throws Exception {
        byte[] bytes={(byte)137,80,78,71,13,10,26,10,1};String hash=CloudRecords.sha256(bytes);
        JSONObject chart=new JSONObject().put("cid",1).put("server_hash","h");
        JSONObject page=new JSONObject().put("image","1-"+hash+".png").put("sha256",hash).put("bytes",bytes.length).put("width",960).put("height",1300);
        JSONObject doc=new JSONObject().put("schema",1).put("server","s").put("cid",1).put("server_hash","h").put("status","ready").put("keys",4).put("notes",3).put("pages",new JSONArray().put(page));
        ChartPreviewData parsed=new ChartPreviewData(doc,"s",chart);ChartPreviewData.verify(parsed.pages.get(0),bytes);
        boolean failed=false;try{new ChartPreviewData(doc,"other",chart);}catch(Exception e){failed=true;}if(!failed)throw new AssertionError("cross-server preview");
        failed=false;try{ChartPreviewData.verify(parsed.pages.get(0),new byte[bytes.length]);}catch(Exception e){failed=true;}if(!failed)throw new AssertionError("corrupt preview");
        page.put("image","../image.png");failed=false;try{new ChartPreviewData(doc,"s",chart);}catch(Exception e){failed=true;}if(!failed)throw new AssertionError("unsafe path");
        System.out.println("Chart preview identity, path and checksum checks passed");
    }
}
