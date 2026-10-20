package io.github.muntashirakon.adb;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercise the real stream code without a socket or Android runtime. */
public final class AdbStreamTest {
    static void field(Object o,String name,Object value)throws Exception {
        Field f=AdbStream.class.getDeclaredField(name);f.setAccessible(true);f.set(o,value);
    }
    static AdbStream stream()throws Exception {
        Class<?> type=Class.forName("sun.misc.Unsafe");Field singleton=type.getDeclaredField("theUnsafe");singleton.setAccessible(true);
        AdbStream s=(AdbStream)type.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),AdbStream.class);
        field(s,"mReadQueue",new ConcurrentLinkedQueue<byte[]>());
        field(s,"mReadBuffer",ByteBuffer.allocate(4096).flip());
        field(s,"mWriteReady",new AtomicBoolean());return s;
    }
    static void check(boolean ok)throws Exception {if(!ok)throw new Exception("stream regression");}
    public static void main(String[] args)throws Exception {
        AdbStream s=stream();InputStream in=s.openInputStream();s.addPayload(new byte[]{1,2,3});
        check(in.read()==1);s.notifyClose(true);
        check(in.read()==2&&in.read()==3&&in.read()==-1); // CLSE while queue is empty but buffer isn't
        s=stream();in=s.openInputStream();s.addPayload(new byte[]{(byte)255});s.addPayload(new byte[]{3,1,0,0,0,0});s.notifyClose(true);
        check(in.read()==255);for(int expected:new int[]{3,1,0,0,0,0})check(in.read()==expected);
        check(in.read()==-1&&in.read(new byte[0])==0);
        final AdbStream blocked=stream();final int[] result={-2};
        Thread reader=new Thread(()->{try{result[0]=blocked.openInputStream().read();}catch(Exception e){result[0]=-3;}});
        reader.start();blocked.notifyClose(true);reader.join(2000);check(!reader.isAlive()&&result[0]==-1);
        System.out.println("PASS: buffered peer close, queued exit frame, unsigned byte, zero length and blocked-reader EOF");
    }
}
