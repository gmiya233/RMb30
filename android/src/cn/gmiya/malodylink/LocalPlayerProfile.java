package cn.gmiya.malodylink;

import android.content.Context;
import android.graphics.*;
import android.graphics.ImageDecoder;
import android.media.ExifInterface;
import android.os.Build;
import android.util.AtomicFile;
import java.io.*;
import java.nio.ByteBuffer;
import org.json.*;
import java.util.*;

/** Device-local appearance, deliberately outside score exports and cloud records. */
public final class LocalPlayerProfile {
    public static final String DEFAULT_TITLE="小师妹";
    public static String ratingTitle(double rating){if(!Double.isFinite(rating)||rating<10)return DEFAULT_TITLE;if(rating<11)return "萌新";if(rating<12)return "高手";if(rating<13)return "大师";return "龙币";}
    public static Map<String,String> ratingTitles(double rating){Map<String,String> choices=new LinkedHashMap<>();choices.put("rank:0",DEFAULT_TITLE);String[] titles={"萌新","高手","大师","龙币"};for(int i=0;i<titles.length;i++)if(Double.isFinite(rating)&&rating>=10+i)choices.put("rank:"+(10+i),titles[i]);return choices;}
    public static double actualRating(Context c,Engine engine)throws Exception{return PosterData.loadRaw(c,engine,PosterPanel.rules(c)).rating();}
    public static Map<String,String> titleChoices(Context c,Engine engine)throws Exception{Map<String,String> choices=ratingTitles(actualRating(c,engine));choices.putAll(eligibleTitles(engine));return choices;}
    private static String key(Context c){String qq=ScoreStore.profile(c);return qq.isEmpty()?"local":qq;}
    private static File avatar(Context c){return new File(c.getFilesDir(),"appearance/"+key(c)+"/avatar.png");}
    public static String nickname(Context c,String fallback){String name=c.getSharedPreferences("appearance",0).getString("name_"+key(c),"").trim();return name.isEmpty()?fallback:name;}
    public static SortedMap<String,String> eligibleTitles(Engine engine){return eligibleTitles(engine.charts(),engine.scores);}
    public static SortedMap<String,String> eligibleTitles(JSONArray charts,JSONArray scores){
        Map<String,JSONObject> active=new HashMap<>();for(int i=0;i<charts.length();i++){JSONObject c=charts.optJSONObject(i);if(c!=null)active.put(c.optInt("cid")+":"+c.optString("server_hash"),c);}
        SortedMap<String,String> result=new TreeMap<>();for(int i=0;i<scores.length();i++){JSONObject s=scores.optJSONObject(i);if(s==null||s.optBoolean("demo")||s.optBoolean("manual"))continue;double a=s.optDouble("accuracy_candidate");if(!Double.isFinite(a)||a<=99.8||a>100)continue;JSONObject chart=active.get(s.optInt("cid")+":"+s.optString("server_hash"));if(chart==null)continue;String title=chart.optString("title").trim();if(title.isEmpty())continue;String id=chart.optInt("sid")>0?"sid:"+chart.optInt("sid"):"cid:"+chart.optInt("cid");result.put(id,title);}return result;
    }
    public static String selectedTitleKey(Context c,Engine engine){android.content.SharedPreferences p=c.getSharedPreferences("appearance",0);return engine.server.equals(p.getString("title_server_"+key(c),""))?p.getString("title_"+key(c),""):"";}
    public static String title(Context c,Engine engine)throws Exception{double rating=actualRating(c,engine);Map<String,String> choices=ratingTitles(rating);choices.putAll(eligibleTitles(engine));return choices.getOrDefault(selectedTitleKey(c,engine),ratingTitle(rating));}
    public static void saveTitle(Context c,Engine engine,String selected)throws Exception {if(!selected.isEmpty()&&!titleChoices(c,engine).containsKey(selected))throw new IOException("该称号尚未解锁，请重新选择");if(!c.getSharedPreferences("appearance",0).edit().putString("title_"+key(c),selected).putString("title_server_"+key(c),engine.server).commit())throw new IOException("称号保存失败");}
    public static Bitmap avatarBitmap(Context c){try(InputStream in=new AtomicFile(avatar(c)).openRead()){return BitmapFactory.decodeStream(in);}catch(Exception missing){return null;}}
    public static Bitmap prepareAvatar(InputStream stream)throws Exception {
        byte[] bytes=Engine.read(stream,12*1024*1024);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>16384||bounds.outHeight>16384)throw new IOException("请选择有效图片，最长边不超过16384像素");
        Bitmap decoded;
        if(Build.VERSION.SDK_INT>=28){decoded=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)),(decoder,info,source)->{decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);decoder.setTargetSampleSize(Math.max(1,(Math.max(info.getSize().getWidth(),info.getSize().getHeight())+1023)/1024));});}
        else{
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>1024)options.inSampleSize*=2;decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(decoded==null)throw new IOException("无法解码图片");
            int orientation=1;try{orientation=new ExifInterface(new ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION,1);}catch(IOException ignored){}
            Matrix matrix=new Matrix();switch(orientation){case 2:matrix.setScale(-1,1);break;case 3:matrix.setRotate(180);break;case 4:matrix.setScale(1,-1);break;case 5:matrix.setRotate(90);matrix.postScale(-1,1);break;case 6:matrix.setRotate(90);break;case 7:matrix.setRotate(-90);matrix.postScale(-1,1);break;case 8:matrix.setRotate(-90);break;}
            if(!matrix.isIdentity()){Bitmap rotated=Bitmap.createBitmap(decoded,0,0,decoded.getWidth(),decoded.getHeight(),matrix,true);if(rotated!=decoded)decoded.recycle();decoded=rotated;}
        }
        Bitmap square=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);int side=Math.min(decoded.getWidth(),decoded.getHeight()),x=(decoded.getWidth()-side)/2,y=(decoded.getHeight()-side)/2;new Canvas(square).drawBitmap(decoded,new Rect(x,y,x+side,y+side),new Rect(0,0,512,512),new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));decoded.recycle();return square;
    }
    public static void save(Context c,String name,Bitmap image,boolean changeAvatar)throws Exception {
        if(name.length()>40)throw new IOException("昵称最多40个字符");
        if(changeAvatar){File f=avatar(c);if(!f.getParentFile().isDirectory()&&!f.getParentFile().mkdirs())throw new IOException("无法保存本地头像");AtomicFile atomic=new AtomicFile(f);
            if(image==null)atomic.delete();else{FileOutputStream out=null;try{out=atomic.startWrite();if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("头像保存失败");atomic.finishWrite(out);}catch(Exception e){if(out!=null)atomic.failWrite(out);throw e;}}
        }
        if(!c.getSharedPreferences("appearance",0).edit().putString("name_"+key(c),name.trim()).commit())throw new IOException("昵称保存失败，请重试");
    }
}
