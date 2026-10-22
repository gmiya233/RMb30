package cn.gmiya.malodylink;

import org.json.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded community payloads. Reviews are independent of score archives. */
public final class CommunityData {
    public static final String[] IDS={"total","song_hot","song_good","creator_hot","creator_good"};
    public static final String[] TITLES={"总榜","歌曲热度榜","歌曲好评榜","谱师热度榜","谱师好评榜"};
    public static final int MAX_BYTES=2*1024*1024;
    public static String topic(SongCatalogue.Song song){return song.sid>0?"s"+song.sid:"c"+(-song.sid);}
    public static String topic(String value){if(value==null||!value.matches("[sc][1-9][0-9]*"))throw new IllegalArgumentException("无效歌曲编号");return value;}
    public static String title(String id){if(id.equals("creator_hot_all"))return "谱师热度榜";for(int i=0;i<IDS.length;i++)if(IDS[i].equals(id))return TITLES[i];throw new IllegalArgumentException("无效榜单");}
    public static boolean good(String id){return id.endsWith("_good");}
    public static final class Boards {public JSONObject doc;public boolean cached;public JSONArray rows(String id)throws Exception{title(id);return doc.getJSONObject("boards").getJSONArray(id);}}
    private static JSONObject document(byte[] bytes,String server,String revision)throws Exception {
        if(bytes.length>MAX_BYTES)throw new IOException("社区摘要超过容量");JSONObject doc=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        if(doc.optInt("schema")!=1||!server.equals(doc.optString("server"))||!revision.equals(doc.optString("revision")))throw new IOException("社区摘要与当前谱库不匹配");
        long updated=doc.getLong("updated");if(updated<=0||updated>System.currentTimeMillis()+300000)throw new IOException("社区摘要时间无效");return doc;
    }
    public static Boards boards(byte[] bytes,String server,String revision)throws Exception {
        JSONObject doc=document(bytes,server,revision);JSONObject boards=doc.getJSONObject("boards");
        List<String> ids=new ArrayList<>(Arrays.asList(IDS));if(boards.has("creator_hot_all"))ids.add("creator_hot_all");
        for(String id:ids){JSONArray rows=boards.getJSONArray(id);if(rows.length()>50)throw new IOException("榜单超过前50名容量");Set<String> seen=new HashSet<>();
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getInt("rank")<1||row.getInt("rank")>50)throw new IOException("榜单名次无效");
                String identity;if(id.equals("total")){RankingData.Player p=RankingData.decode(row);identity=p.qq;if(!number(p.rating)||!number(p.oldRating)||!number(p.newRating)||p.oldCount<0||p.oldCount>20||p.newCount<0||p.newCount>10)throw new IOException("战力数据无效");}
                else{identity=row.getString("id");if(id.startsWith("song_")){topic(identity);if(row.getInt("cid")<=0)throw new IOException("歌曲谱面无效");}else if(!identity.matches("[a-f0-9]{64}"))throw new IOException("谱师编号无效");
                    double average=row.getDouble("average");int votes=row.getInt("rating_count");if(!number(average)||average>5||votes<0||(votes==0&&average!=0)||(votes>0&&average<1)||row.getLong("downloads")<0)throw new IOException("榜单统计无效");
                }
                if(!seen.add(identity)||row.getString("name").length()>512)throw new IOException("榜单条目无效");
            }
        }Boards result=new Boards();result.doc=doc;return result;
    }
    private static boolean number(double value){return Double.isFinite(value)&&value>=0;}
    public static long chartDownloads(JSONObject doc,int cid){JSONObject counts=doc.optJSONObject("chart_downloads");return counts==null||!counts.has(Integer.toString(cid))?-1:counts.optLong(Integer.toString(cid),-1);}
    public static JSONObject song(byte[] bytes,String server,String revision,String topic)throws Exception {
        JSONObject doc=document(bytes,server,revision);if(!topic(topic).equals(doc.getString("topic")))throw new IOException("歌曲评论不匹配");
        double average=doc.getDouble("average");int count=doc.getInt("rating_count");JSONArray reviews=doc.getJSONArray("reviews");
        if(!number(average)||average>5||count<0||(count==0&&average!=0)||(count>0&&average<1)||reviews.length()>200||reviews.length()>count||doc.getLong("downloads")<0)throw new IOException("歌曲评分统计无效");
        if(doc.has("chart_downloads")){JSONObject downloads=doc.getJSONObject("chart_downloads");if(downloads.length()>4096)throw new IOException("谱面下载统计超过容量");Iterator<String> keys=downloads.keys();while(keys.hasNext()){String cid=keys.next();Object value=downloads.get(cid);if(!cid.matches("[1-9][0-9]*")||!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue())||((Number)value).doubleValue()<0||((Number)value).doubleValue()!=Math.rint(((Number)value).doubleValue()))throw new IOException("谱面下载统计无效");}}
        Set<String> seen=new HashSet<>();for(int i=0;i<reviews.length();i++){JSONObject review=reviews.getJSONObject(i);validateReview(review);if(!topic.equals(review.getString("topic"))||!seen.add(review.getString("qq"))||review.getLong("updated")<0)throw new IOException("评论数据无效");}
        return doc;
    }
    public static JSONObject review(String server,String topic,String qq,String name,int stars,String text)throws Exception {
        JSONObject review=new JSONObject().put("schema",1).put("server",server).put("topic",topic(topic)).put("qq",CloudRecords.qq(qq)).put("name",name.trim()).put("stars",stars).put("text",text.trim()).put("nonce",UUID.randomUUID().toString());validateReview(review);return review;
    }
    public static void validateReview(JSONObject review)throws Exception {
        topic(review.getString("topic"));CloudRecords.qq(review.getString("qq"));double stars=review.getDouble("stars");
        if(stars<1||stars>5||stars!=Math.rint(stars)||review.getString("text").length()>500||review.getString("name").trim().isEmpty()||review.getString("name").length()>128||!review.getString("nonce").matches("[a-f0-9-]{36}"))throw new IOException("请填写1～5星评分和500字以内的评论");
    }
}
