package cn.gmiya.malodylink;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** One 24-unit outline family for the three navigation destinations. */
public final class NavIcon extends Drawable {
    private final int kind;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public NavIcon(int kind,int color){this.kind=kind;paint.setColor(color);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.8f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);}
    @Override public void draw(Canvas c){c.save();Rect r=getBounds();c.translate(r.left,r.top);c.scale(r.width()/24f,r.height()/24f);Path p=new Path();
        if(kind==0){p.moveTo(3,10);p.lineTo(12,3);p.lineTo(21,10);p.moveTo(5,9);p.lineTo(5,21);p.lineTo(10,21);p.lineTo(10,15);p.lineTo(14,15);p.lineTo(14,21);p.lineTo(19,21);p.lineTo(19,9);c.drawPath(p,paint);}
        else if(kind==1){c.drawRoundRect(3,3,21,21,3,3,paint);c.drawLine(7,16,7,12,paint);c.drawLine(12,16,12,7,paint);c.drawLine(17,16,17,10,paint);}
        else if(kind==3){p.moveTo(4,3);p.lineTo(17,3);p.lineTo(21,7);p.lineTo(21,21);p.lineTo(3,21);p.lineTo(3,3);c.drawPath(p,paint);c.drawRect(7,3,16,9,paint);c.drawRoundRect(7,14,17,21,1,1,paint);}
        else if(kind==4){c.drawArc(4,4,20,20,35,245,false,paint);c.drawArc(4,4,20,20,215,65,false,paint);p.moveTo(20,5);p.lineTo(20,10);p.lineTo(15,10);p.moveTo(4,19);p.lineTo(4,14);p.lineTo(9,14);c.drawPath(p,paint);}
        else if(kind==6){c.drawCircle(12,9,5,paint);p.moveTo(8,13);p.lineTo(6,22);p.lineTo(12,18);p.lineTo(18,22);p.lineTo(16,13);c.drawPath(p,paint);}
        else if(kind==7){c.drawLine(5,3,5,7,paint);c.drawCircle(5,10,2.5f,paint);c.drawLine(5,13,5,21,paint);c.drawLine(12,3,12,12,paint);c.drawCircle(12,15,2.5f,paint);c.drawLine(12,18,12,21,paint);c.drawLine(19,3,19,5,paint);c.drawCircle(19,8,2.5f,paint);c.drawLine(19,11,19,21,paint);}
        else if(kind==8){p.moveTo(9,3);p.lineTo(3,3);p.lineTo(3,9);p.moveTo(15,3);p.lineTo(21,3);p.lineTo(21,9);p.moveTo(3,15);p.lineTo(3,21);p.lineTo(9,21);p.moveTo(21,15);p.lineTo(21,21);p.lineTo(15,21);c.drawPath(p,paint);c.drawLine(9,12,15,12,paint);c.drawLine(12,9,12,15,paint);}
        else{c.drawCircle(12,12,6.3f,paint);c.drawCircle(12,12,2.5f,paint);for(int i=0;i<8;i++){double a=i*Math.PI/4;c.drawLine(12+(float)Math.cos(a)*6.5f,12+(float)Math.sin(a)*6.5f,12+(float)Math.cos(a)*9,12+(float)Math.sin(a)*9,paint);}}
        c.restore();}
    @Override public void setAlpha(int a){paint.setAlpha(a);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter f){paint.setColorFilter(f);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
