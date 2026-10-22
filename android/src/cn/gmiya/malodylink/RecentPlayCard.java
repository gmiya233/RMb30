package cn.gmiya.malodylink;

import android.content.Context;
import android.graphics.Typeface;
import android.view.*;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Accessible native card using the same jacket/level/judge/rating hierarchy as B30. */
public final class RecentPlayCard extends LinearLayout {
    private static final int NAVY=AppUi.INK,MUTED=AppUi.MUTED,CYAN=AppUi.ACCENT;
    public RecentPlayCard(Context c,RecentPlays.Play play,int position,SongCatalogue.Song song){
        super(c);setOrientation(VERTICAL);setPadding(AppUi.dp(c,12),AppUi.dp(c,12),AppUi.dp(c,12),AppUi.dp(c,12));android.graphics.drawable.GradientDrawable surface=AppUi.background(c,AppUi.CARD);surface.setStroke(AppUi.dp(c,1),AppUi.BORDER);setBackground(surface);
        LinearLayout top=new LinearLayout(c);top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView art=new ImageView(c);art.setScaleType(ImageView.ScaleType.CENTER_CROP);art.setBackground(AppUi.background(c,AppUi.SOFT));art.setClipToOutline(true);art.setContentDescription("曲绘");top.addView(art,new LayoutParams(AppUi.dp(c,64),AppUi.dp(c,64)));if(song!=null)SongArtwork.load(c,art,song);
        LinearLayout info=AppUi.column(c,0);LayoutParams ip=new LayoutParams(0,-2,1);ip.leftMargin=AppUi.dp(c,10);top.addView(info,ip);
        TextView title=AppUi.text(c,play.chart.optString("title"),16,NAVY,true);title.setMaxLines(2);info.addView(title);
        String level=Double.isFinite(play.level)?"Lv."+(play.level==Math.rint(play.level)?Integer.toString((int)play.level):Double.toString(play.level)):"待定级";
        int sc=ChartDetails.sc(play.chart.optString("version"));if(sc>0)level+=" · SC"+sc;
        TextView badge=AppUi.text(c,level,13,CYAN,true);info.addView(badge);addView(top);AppUi.space(this,8);
        addView(ChartKeyBadge.view(c,play.chart,24));AppUi.space(this,4);
        TextView score=AppUi.text(c,String.format(Locale.ROOT,"%,d",play.record.optLong("score_candidate")),26,NAVY,true);score.setMaxLines(1);score.setAutoSizeTextTypeUniformWithConfiguration(16,26,1,android.util.TypedValue.COMPLEX_UNIT_SP);addView(score);
        addView(AppUi.text(c,RatingRules.judgeName(play.judge)+"  ·  准度 "+String.format(Locale.ROOT,"%.3f%%",play.record.optDouble("accuracy_candidate")),13,MUTED,false));
        addView(AppUi.text(c,"rating  "+(Double.isFinite(play.rating)?String.format(Locale.ROOT,"%.4f",play.rating):"待定"),18,CYAN,true));
        String time=play.record.optLong("played_at")>0?new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(play.record.optLong("played_at")*1000)):"时间未知";
        AppUi.space(this,6);addView(AppUi.text(c,String.format(Locale.ROOT,"%02d",position)+"  ·  "+time+"  ·  试算",12,MUTED,false));
    }
}
