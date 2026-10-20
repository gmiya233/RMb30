package cn.gmiya.malodylink;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

/** Some vivo versions unlock the developer entry separately from the master switch. */
public final class DeveloperModeState {
    public static boolean masterEnabled(Context context){try{return Settings.Global.getInt(context.getContentResolver(),Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,0)==1;}catch(Exception unavailable){return false;}}
    public static boolean unlocked(Context context){
        if(masterEnabled(context))return true;
        if(Build.MANUFACTURER.equalsIgnoreCase("vivo")||Build.BRAND.equalsIgnoreCase("iqoo"))try{
            return Settings.Global.getInt(context.getContentResolver(),"vivo_development_show",0)==1;
        }catch(Exception unavailable){}
        return false;
    }
}
