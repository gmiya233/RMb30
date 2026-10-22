package cn.gmiya.malodylink;

import android.content.Context;
import android.graphics.*;
import android.os.*;
import android.widget.ImageView;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Server-published thumbnails under the dedicated Rank1 directory; no Data1 image reads. */
public final class SongArtwork {
    private static final ExecutorService IO=Executors.newFixedThreadPool(3);private static final Handler UI=new Handler(Looper.getMainLooper());
    private static final Map<String,Long> MISSING=new ConcurrentHashMap<>();private static OpenListScoreRemote source;private static long connectedAt;
    private static final android.util.LruCache<String,Bitmap> MEMORY=new android.util.LruCache<String,Bitmap>(12288){protected int sizeOf(String key,Bitmap bitmap){return Math.max(1,bitmap.getByteCount()/1024);}};
    private static synchronized OpenListScoreRemote remote(Context c)throws Exception{if(source==null||System.currentTimeMillis()-connectedAt>3600000){source=RankConnection.open(c);connectedAt=System.currentTimeMillis();}return source;}
    private static Bitmap decode(InputStream in)throws Exception{byte[] b=Engine.read(in,8*1024*1024);BitmapFactory.Options size=new BitmapFactory.Options();size.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(b,0,b.length,size);if(size.outWidth<=0||size.outHeight<=0)return null;BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=Math.max(1,Math.max(size.outWidth,size.outHeight)/512);return BitmapFactory.decodeByteArray(b,0,b.length,o);}
    public static void load(Context context,ImageView view,SongCatalogue.Song song){Context c=context.getApplicationContext();String marker=song.sid+":"+song.cover+":"+song.background;view.setTag(marker);Bitmap cached=MEMORY.get(marker);if(cached!=null){view.setImageBitmap(cached);return;}view.setImageResource(android.R.drawable.ic_media_play);IO.execute(()->{Bitmap bitmap=MEMORY.get(marker);
        if(bitmap==null)for(JSONObject chart:song.charts){int cid=chart.optInt("cid");try{File local=new File(c.getFilesDir(),"jackets/"+cid+".img");bitmap=decode(local.exists()?new FileInputStream(local):c.getAssets().open("jackets/"+cid+".img"));if(bitmap!=null)break;}catch(Exception missing){}}
        if(bitmap==null)for(String path:new String[]{song.cover,song.background}){if(path==null||path.isEmpty())continue;try{String clean=path.replace('\\','/').replaceAll("^/+","");if(Arrays.asList(clean.split("/")).contains(".."))continue;String hash=CloudRecords.sha256(clean.getBytes(StandardCharsets.UTF_8));File folder=new File(c.getCacheDir(),"song-art");folder.mkdirs();File image=new File(folder,hash+".jpg");if(image.exists())bitmap=decode(new FileInputStream(image));else if(System.currentTimeMillis()-MISSING.getOrDefault(hash,0L)>300000){byte[] bytes=remote(c).songArt(hash);bitmap=decode(new ByteArrayInputStream(bytes));if(bitmap!=null)try(FileOutputStream out=new FileOutputStream(image)){out.write(bytes);}}if(bitmap!=null)break;MISSING.put(hash,System.currentTimeMillis());}catch(Exception unavailable){try{MISSING.put(CloudRecords.sha256(path.replaceAll("^/+","").getBytes(StandardCharsets.UTF_8)),System.currentTimeMillis());}catch(Exception ignored){}}}
        final Bitmap result=bitmap;if(result!=null)MEMORY.put(marker,result);UI.post(()->{if(marker.equals(view.getTag())&&result!=null)view.setImageBitmap(result);});});}
}
