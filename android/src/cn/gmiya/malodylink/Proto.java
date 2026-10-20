package cn.gmiya.malodylink;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.nio.charset.StandardCharsets;

/** Wire reader. Semantic field mappings are experimental for Malody V 6.6.42. */
public final class Proto {
    public static class Field {
        public int number, wire; public Object value;
        Field(int n,int w,Object v){number=n;wire=w;value=v;}
    }
    private static long varint(byte[] b,int[] p) throws IOException {
        long v=0;
        for(int s=0;s<70;s+=7){
            if(p[0]>=b.length)throw new IOException("二进制记录不完整");
            int x=b[p[0]++]&255; v|=(long)(x&127)<<s;
            if(x<128)return v;
        }
        throw new IOException("无效二进制记录");
    }
    public static List<Field> decode(byte[] b) throws IOException {
        List<Field> out=new ArrayList<>(); int[] p={0};
        while(p[0]<b.length){
            long tag=varint(b,p); int n=(int)(tag>>>3),w=(int)(tag&7); Object v;
            if(n==0)throw new IOException("无效字段编号");
            if(w==0)v=varint(b,p);
            else if(w==2){long l=varint(b,p); if(l<0||l>b.length-p[0])throw new IOException("字段长度异常");
                v=Arrays.copyOfRange(b,p[0],p[0]+(int)l); p[0]+=(int)l;
            } else if(w==5||w==1){int len=w==5?4:8; if(b.length-p[0]<len)throw new IOException("字段截断");
                ByteBuffer buf=ByteBuffer.wrap(b,p[0],len).order(ByteOrder.LITTLE_ENDIAN);
                if(w==5)v=Float.valueOf(buf.getFloat());else v=Double.valueOf(buf.getDouble());p[0]+=len;
            } else throw new IOException("暂不支持的记录类型");
            out.add(new Field(n,w,v));
        }
        return out;
    }
    public static Object get(List<Field> fs,int n){for(Field f:fs)if(f.number==n)return f.value;return null;}
    public static String text(List<Field> fs,int n){Object v=get(fs,n);return v instanceof byte[]?new String((byte[])v,StandardCharsets.UTF_8):"";}
    public static long integer(List<Field> fs,int n){Object v=get(fs,n);return v instanceof Number?((Number)v).longValue():0;}
    public static double real(List<Field> fs,int n){Object v=get(fs,n);return v instanceof Number?((Number)v).doubleValue():0;}
    /** Extract only the identity field before looking at score payloads. */
    public static String replayHash(byte[] b) throws IOException {return text(decode(b),5);}
}
