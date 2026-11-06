package cn.gmiya.malodylink;

import android.content.Context;
import android.graphics.Typeface;

/** User-selected B pairing: Noto Sans SC + Rajdhani, embedded for consistency. */
public final class RadarFonts {
    private static RadarFonts instance;
    public final Typeface chinese,medium,latin,bold;
    private RadarFonts(Context context){
        chinese=new Typeface.Builder(context.getAssets(),"radar-fonts/NotoSansSC.ttf").setFontVariationSettings("'wght' 400").build();
        medium=new Typeface.Builder(context.getAssets(),"radar-fonts/NotoSansSC.ttf").setFontVariationSettings("'wght' 600").build();
        latin=Typeface.createFromAsset(context.getAssets(),"radar-fonts/Rajdhani-SemiBold.ttf");
        bold=Typeface.createFromAsset(context.getAssets(),"radar-fonts/Rajdhani-Bold.ttf");
    }
    public static synchronized RadarFonts get(Context context){if(instance==null)instance=new RadarFonts(context.getApplicationContext());return instance;}
    public Typeface forText(String value,boolean heavy){for(int i=0;i<value.length();i++)if(value.charAt(i)>127)return heavy?medium:chinese;return heavy?bold:latin;}
}
