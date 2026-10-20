package cn.gmiya.malodylink;

import android.content.*;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.*;

public final class GallerySaver {
    public static Uri save(Context context,Bitmap bitmap,boolean demo)throws IOException {
        String name="节奏大师-O20-N10-"+(demo?"示例-":"成绩-")+System.currentTimeMillis()+".png";
        if(Build.VERSION.SDK_INT>=29){
            ContentResolver resolver=context.getContentResolver();ContentValues values=new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,name);values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/Malody Link");values.put(MediaStore.Images.Media.IS_PENDING,1);
            Uri uri=resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values);
            if(uri==null)throw new IOException("无法创建相册图片");
            try{
                try(OutputStream out=resolver.openOutputStream(uri)){if(out==null||!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("图片编码失败");}
                values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);if(resolver.update(uri,values,null,null)!=1)throw new IOException("无法完成相册保存");return uri;
            }catch(Exception e){resolver.delete(uri,null,null);throw new IOException("相册保存失败",e);}
        }
        File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"Malody Link");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建相册目录");File target=new File(dir,name);
        try(FileOutputStream out=new FileOutputStream(target)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("图片编码失败");}
        catch(IOException e){target.delete();throw e;}
        MediaScannerConnection.scanFile(context,new String[]{target.getAbsolutePath()},new String[]{"image/png"},null);return Uri.fromFile(target);
    }
}
