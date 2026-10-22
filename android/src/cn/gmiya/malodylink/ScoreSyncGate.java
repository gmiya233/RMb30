package cn.gmiya.malodylink;

import java.util.concurrent.atomic.AtomicReference;

/** A local scan and a standalone cloud sync must not write score files concurrently. */
public final class ScoreSyncGate {
    private static final AtomicReference<String> owner=new AtomicReference<>();
    public static boolean enter(String value){return owner.compareAndSet(null,value);}
    public static void leave(String value){owner.compareAndSet(value,null);}
    public static boolean busy(){return owner.get()!=null;}
}
