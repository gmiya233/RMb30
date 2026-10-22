package cn.gmiya.malodylink;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

public final class AppUi {
    public static final int BG=0xff0f172a,CARD=0xff19263b,INK=0xfff1f5f9,MUTED=0xffb1bfd2,ACCENT=0xff74e4d6;
    public static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    public static TextView text(Context c,String value,int size,int color,boolean bold){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);t.setLineSpacing(dp(c,2),1);return t;}
    public static GradientDrawable background(Context c,int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,16));return d;}
    public static Button button(Context c,String label,Runnable action){Button b=new Button(c);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(INK);b.setMinHeight(dp(c,48));b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff25465c));b.setOnClickListener(v->action.run());return b;}
    public static LinearLayout column(Context c,int padding){LinearLayout l=new LinearLayout(c);l.setOrientation(1);l.setPadding(dp(c,padding),dp(c,padding),dp(c,padding),dp(c,padding));return l;}
    public static void space(LinearLayout p,int height){p.addView(new View(p.getContext()),new LinearLayout.LayoutParams(1,dp(p.getContext(),height)));}
}
