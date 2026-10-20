package cn.gmiya.malodylink;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Redrawn system-screen illustrations; no device screenshot or personal data is bundled. */
public final class ConnectionGuideView extends View {
    private final int step;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public ConnectionGuideView(Context context,int step){
        super(context);this.step=step;
        setContentDescription(step==1?
            "操作图一：开发者选项中的无线调试。开启右侧开关后，直接点左侧无线调试文字或整行空白区域进入详情；不要只点开关。":
            "操作图二：无线调试详情页。点使用配对码配对设备，显示六位配对码；保持弹窗打开，在通知中输入。图片为示意，不是真实系统控件。");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int width=MeasureSpec.getSize(widthSpec);
        setMeasuredDimension(width,resolveSize(Math.round(width*0.59f),heightSpec));
    }
    private void box(Canvas c,float l,float t,float r,float b,int color,float radius){paint.setColor(color);paint.setStyle(Paint.Style.FILL);c.drawRoundRect(l,t,r,b,radius,radius,paint);}
    private void text(Canvas c,String value,float x,float y,float size,int color,boolean bold){
        paint.setStyle(Paint.Style.FILL);paint.setColor(color);paint.setTextSize(size);paint.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);c.drawText(value,x,y,paint);
    }
    private void highlight(Canvas c,float l,float t,float r,float b){
        paint.setColor(0xff16814b);paint.setStrokeWidth(3);paint.setStyle(Paint.Style.STROKE);c.drawRoundRect(l,t,r,b,12,12,paint);paint.setStyle(Paint.Style.FILL);
    }
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);
        canvas.save();canvas.scale(getWidth()/600f,getWidth()/600f);
        box(canvas,0,0,600,354,0xfff3f5f7,16);
        text(canvas,"操作示意图 · 系统页面",24,33,15,0xff566370,false);
        text(canvas,"‹",24,81,34,0xff202b35,false);
        text(canvas,step==1?"开发者选项":"无线调试",64,77,25,0xff18232d,true);
        if(step==1){
            box(canvas,18,100,582,224,Color.WHITE,12);
            text(canvas,"无线调试",38,143,25,0xff18232d,true);
            text(canvas,"通过 Wi-Fi 使用调试模式",38,177,18,0xff64717d,false);
            box(canvas,494,131,558,165,0xff16814b,18);paint.setColor(Color.WHITE);canvas.drawCircle(541,148,12,paint);
            highlight(canvas,28,111,474,204);
            text(canvas,"点文字或这一行，进入详情",34,263,24,0xff12683e,true);
            paint.setColor(0xff16814b);paint.setStrokeWidth(3);canvas.drawLine(204,237,204,211,paint);
            text(canvas,"右侧开关只负责开启，不是进入按钮",34,306,19,0xff475563,false);
        }else{
            box(canvas,18,100,582,254,Color.WHITE,12);
            text(canvas,"使用二维码配对设备",38,143,23,0xff475563,false);
            paint.setColor(0xffe6eaee);canvas.drawRect(38,164,560,165,paint);
            text(canvas,"使用配对码配对设备",38,206,25,0xff18232d,true);
            text(canvas,"›",540,207,27,0xff475563,false);
            highlight(canvas,28,174,572,240);
            text(canvas,"点这一项，显示六位配对码",34,288,24,0xff12683e,true);
            text(canvas,"保持弹窗打开，下拉通知输入配对码",34,325,19,0xff475563,false);
        }
        canvas.restore();
    }
}
