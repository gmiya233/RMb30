package cn.gmiya.malodylink;

/** State selection is separate from rendering and never changes system settings. */
public final class SetupGuide {
    public static final int DEVELOPER=0,WIRELESS=1,PAIR=2,READY=3;
    public static int stage(boolean unlocked,boolean master,boolean wireless,boolean paired){
        if(!unlocked||!master)return DEVELOPER;
        if(!wireless)return WIRELESS;
        return paired?READY:PAIR;
    }
}
