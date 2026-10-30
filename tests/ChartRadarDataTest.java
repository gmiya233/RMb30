import cn.gmiya.malodylink.*;
import org.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class ChartRadarDataTest {
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);}
    private static JSONObject chart(JSONObject row)throws Exception{return new JSONObject().put("cid",row.getInt("cid")).put("server_hash",row.getString("server_hash")).put("keys",row.getInt("keys")).put("title","test").put("level",row.getDouble("normalized_level"));}
    private static void fails(JSONObject doc,String server,JSONObject c)throws Exception{try{ChartRadarData.find(doc,server,c);throw new AssertionError("invalid metadata accepted");}catch(java.io.IOException expected){}}
    public static void main(String[] args)throws Exception {
        JSONObject doc=new JSONObject(new String(Files.readAllBytes(Paths.get("android/assets/chart-radar.json")),StandardCharsets.UTF_8));String server=doc.getString("server");JSONArray rows=doc.getJSONArray("charts");
        check(rows.length()==313,"sample count");int imd=0;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);ChartRadarData data=ChartRadarData.find(doc,server,chart(row));
            check(data.frame==data.level*10-40,"grade frame");
            for(int k=0;k<6;k++){check(data.radius(k,0)==0,"animation starts at center");check(Math.abs(data.radius(k,.5f)*2-data.radius(k,1))<1e-5,"halfway geometry");check(data.radius(k,2)==data.radius(k,1),"animation clamps");}
            if(data.format.equals("IMD")){check(data.values[3]==0&&!data.hell(3),"IMD speed zero");imd++;}
            if(data.cid==600214)check(data.hell(0)&&data.hell(1),"Vitamins HELL axes");
            if(data.cid==604332)check(data.values[3]>450&&data.radius(3,1)==208,"uncapped value with bounded drawing");
        }
        check(imd==46,"IMD coverage");JSONObject c=chart(rows.getJSONObject(0));fails(doc,"another server",c);
        fails(doc,server,new JSONObject(c.toString()).put("server_hash","wrong"));
        fails(doc,server,new JSONObject(c.toString()).put("level",15));
        JSONObject reversed=new JSONObject(doc.toString());reversed.getJSONArray("axes").put(3,"hold");fails(reversed,server,c);
        check(ChartRadarData.centeredOrigin(100,2,42)+22==100,"horizontal ink centering");
        check(ChartRadarData.centeredOrigin(50,-20,0)-10==50,"vertical ink centering");
        System.out.println("PASS: 313 chart identities, 46 IMD speed-zero records, grades, animation geometry and HELL ink centering.");
    }
}
