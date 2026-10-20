package cn.gmiya.malodylink;

import android.os.*;
import java.io.*;
import java.util.*;

/** Shizuku user service. Fixed read-only scope; no shell command or write API. */
public final class ReadOnlyService extends Binder {
    public static final String DESCRIPTOR="cn.gmiya.malodylink.ReadOnlyService";
    public static final String ROOT="/storage/emulated/0/Android/data/me.mugzone.emiria/files";
    public ReadOnlyService(){attachInterface(null,DESCRIPTOR);}
    public void destroy(){System.exit(0);}
    private static File allowed(String path)throws IOException {
        File file=new File(path).getCanonicalFile();String p=file.getPath();
        String lower=p.toLowerCase(Locale.ROOT);boolean chartFile=p.startsWith(ROOT+"/chart/")&&(lower.endsWith(".mc")||lower.endsWith(".jpg")||lower.endsWith(".jpeg")||lower.endsWith(".png")||lower.endsWith(".webp"));
        if(!chartFile&&!(p.startsWith(ROOT+"/replay/")&&p.endsWith(".mrv")))throw new SecurityException("只允许读取 Malody 谱面、曲绘和回放");
        return file;
    }
    private static void walk(File dir,List<String> out,String suffix,int depth){
        if(depth>12||out.size()>=20000)return;File[] files=dir.listFiles();if(files==null)return;
        for(File f:files){if(f.isDirectory())walk(f,out,suffix,depth+1);else if(f.getName().endsWith(suffix))out.add(f.getPath());}
    }
    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException {
        if(code==INTERFACE_TRANSACTION){reply.writeString(DESCRIPTOR);return true;}
        // The Shizuku documented destroy transaction.
        if(code==16777115){reply.writeNoException();destroy();return true;}
        data.enforceInterface(DESCRIPTOR);
        try{
            if(code==1){String type=data.readString();if(!type.equals("chart")&&!type.equals("replay"))throw new SecurityException("无效目录");
                List<String> paths=new ArrayList<>();walk(new File(ROOT+"/"+type),paths,type.equals("chart")?".mc":".mrv",0);
                reply.writeNoException();reply.writeStringList(paths);return true;
            }
            if(code==2){File f=allowed(data.readString());long offset=data.readLong();int length=data.readInt();
                if(offset<0||length<1||length>262144||f.length()>33554432)throw new SecurityException("读取范围异常");
                byte[] bytes;try(RandomAccessFile in=new RandomAccessFile(f,"r")){if(offset>=in.length())bytes=new byte[0];else{in.seek(offset);bytes=new byte[(int)Math.min(length,in.length()-offset)];in.readFully(bytes);}}
                reply.writeNoException();reply.writeByteArray(bytes);return true;
            }
        }catch(Exception e){reply.writeException(new IllegalStateException(e.getMessage()));return true;}
        return super.onTransact(code,data,reply,flags);
    }
}
