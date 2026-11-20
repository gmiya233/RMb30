package cn.gmiya.malodylink;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Resolution-independent medal; rank remains readable without color. */
final class RankMedal extends View {
    private final int rank;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    RankMedal(Context context,int rank){super(context);this.rank=rank;setContentDescription("第"+rank+"名奖牌");}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.save();canvas.scale(getWidth()/48f,getHeight()/48f);
        int color=rank==1?0xffc58a15:rank==2?0xff78909c:0xffb1764d;
        paint.setColor(color);Path ribbon=new Path();ribbon.moveTo(11,3);ribbon.lineTo(20,3);ribbon.lineTo(28,20);ribbon.lineTo(19,23);ribbon.close();canvas.drawPath(ribbon,paint);
        ribbon.reset();ribbon.moveTo(28,3);ribbon.lineTo(37,3);ribbon.lineTo(29,23);ribbon.lineTo(20,20);ribbon.close();canvas.drawPath(ribbon,paint);
        paint.setColor(rank==1?0xffffe4a0:rank==2?0xffe1e8ed:0xfff0d3bd);canvas.drawCircle(24,29,16,paint);
        paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);canvas.drawCircle(24,29,15,paint);paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT,Typeface.BOLD));paint.setTextSize(19);paint.setTextAlign(Paint.Align.CENTER);Paint.FontMetrics fm=paint.getFontMetrics();canvas.drawText(Integer.toString(rank),24,29-(fm.ascent+fm.descent)/2,paint);canvas.restore();
    }
}
