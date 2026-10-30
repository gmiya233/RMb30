package cn.gmiya.malodylink;

import java.io.IOException;
import java.util.Locale;
import org.json.*;

/** Derived chart attributes bound to the active server and chart revision. */
public final class ChartRadarData {
    public static final String[] AXES={"notes","chord","peak","speed","hold","slide"};
    public static final String[] LABELS={"物量","同押","爆发","变速","长按复合","滑键"};
    public final int cid,keys;
    public final String title,format,updated;
    public final double level,frame;
    public final double[] values;
    private ChartRadarData(JSONObject row,JSONObject chart,String date)throws Exception {
        cid=row.getInt("cid");keys=row.getInt("keys");format=row.getString("source_format");updated=date;
        title=chart.optString("title","未命名歌曲");level=row.getDouble("normalized_level");frame=frameForLevel(level);
        double current=ChartDetails.level(SongCatalogue.rawLevel(chart),chart.optString("version"));
        if(!Double.isFinite(current)||Math.abs(current-level)>1e-8)throw new IOException("谱面定级已更新，请等待六维数据更新");
        int currentKeys=ChartKeys.count(chart);
        if(keys<4||keys>6||(currentKeys>0&&currentKeys!=keys))throw new IOException("六维数据键数不匹配");
        JSONArray numbers=row.getJSONArray("values");
        if(numbers.length()!=6)throw new IOException("六维数据不完整");
        values=new double[6];
        for(int i=0;i<6;i++){values[i]=numbers.getDouble(i);if(!Double.isFinite(values[i])||values[i]<0||values[i]>1000000)throw new IOException("六维数值无效");}
        if("IMD".equals(format))values[3]=0;
    }
    public static ChartRadarData find(JSONObject doc,String server,JSONObject chart)throws Exception {
        if(doc.optInt("schema")!=1||!server.equals(doc.optString("server"))||!"per-keys-top5-mean-candidate".equals(doc.optString("scale")))throw new IOException("六维数据与服务器不匹配");
        JSONArray axes=doc.optJSONArray("axes");
        if(axes==null||axes.length()!=6)throw new IOException("六维坐标无效");
        for(int i=0;i<6;i++)if(!AXES[i].equals(axes.optString(i)))throw new IOException("六维坐标顺序不匹配");
        JSONArray rows=doc.getJSONArray("charts");
        if(rows.length()>10000)throw new IOException("六维数据过大");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            if(row.optInt("cid")!=chart.optInt("cid"))continue;
            String hash=row.optString("server_hash");
            if(hash.isEmpty()||!hash.equals(chart.optString("server_hash")))throw new IOException("谱面内容已更新，请等待六维数据更新");
            return new ChartRadarData(row,chart,doc.optString("updated"));
        }
        throw new IOException("这张谱面暂未完成六维分析，目前提供10～15级样本");
    }
    public static double frameForLevel(double level){
        if(!Double.isFinite(level)||level<10||level>15)throw new IllegalArgumentException("六维样本等级须为10～15级");
        return level*10-40;
    }
    public boolean hell(int axis){return values[axis]>frame+1e-8;}
    public float radius(int axis,float reveal){return (float)(Math.min(208,values[axis]/frame*116)*Math.max(0,Math.min(1,reveal)));}
    public String number(int axis){return String.format(Locale.ROOT,"%.1f",values[axis]);}
    public String description(){StringBuilder out=new StringBuilder(title+"，"+keys+"键，等级"+level+"，参考框"+frame);for(int i=0;i<6;i++)out.append("，").append(LABELS[i]).append(number(i)).append(hell(i)?" HELL":"");return out.toString();}
    /** Baseline offset that centers the ink bounds rather than the font advance. */
    public static float centeredOrigin(float center,float minimum,float maximum){return center-(minimum+maximum)/2f;}
}
