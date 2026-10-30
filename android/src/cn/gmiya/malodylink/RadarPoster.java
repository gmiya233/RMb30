package cn.gmiya.malodylink;

import android.content.Context;
import android.graphics.*;

/** Native arcade radar. Coordinates match the selected 620 x 1100 concept. */
public final class RadarPoster {
    public static final int WIDTH=620,HEIGHT=1100;
    private static final int BG=0xff080c18,INK=0xfff6f7fc,MUTED=0xffa9b5ca,GRID=0xff343c54,PINK=0xffff527d;
    private static final int[] COLORS={0xfffa8edf,0xffc9ff73,0xffffe398,0xff7de6ff,0xffbcb0ff,0xffffad85};
    private final RadarFonts fonts;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
    private final BitmapShader texture;
    private Canvas c;
    public RadarPoster(Context context){fonts=RadarFonts.get(context);Bitmap tile=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);tile.setPixel(4,6,0xff121724);texture=new BitmapShader(tile,Shader.TileMode.REPEAT,Shader.TileMode.REPEAT);}
    public Bitmap render(ChartRadarData row){
        Bitmap result=Bitmap.createBitmap(WIDTH*2,HEIGHT*2,Bitmap.Config.ARGB_8888);Canvas target=new Canvas(result);target.scale(2,2);draw(target,row,1);return result;
    }
    public void draw(Canvas target,ChartRadarData row,float reveal){c=target;c.drawColor(BG);draw(row,reveal);c=null;}
    private void color(int value){p.setColor(value);p.setStyle(Paint.Style.FILL);p.setPathEffect(null);p.setShader(null);p.setTypeface(null);}
    private void rect(float x,float y,float w,float h,int value){color(value);c.drawRect(x,y,x+w,y+h,p);}
    private void line(float x,float y,float xx,float yy,int value,float width,boolean dash){color(value);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(width);if(dash)p.setPathEffect(new DashPathEffect(new float[]{4,5},0));c.drawLine(x,y,xx,yy,p);p.setPathEffect(null);}
    private void path(float[][] points,int value,float width,boolean fill){color(value);p.setStyle(fill?Paint.Style.FILL:Paint.Style.STROKE);p.setStrokeWidth(width);p.setStrokeJoin(Paint.Join.ROUND);Path path=new Path();path.moveTo(points[0][0],points[0][1]);for(int i=1;i<points.length;i++)path.lineTo(points[i][0],points[i][1]);path.close();c.drawPath(path,p);}
    private void dot(float x,float y,float radius,int value){color(value);c.drawCircle(x,y,radius,p);}
    private Paint textPaint(String text,float size,int value,boolean bold){color(value);p.setTextSize(size);p.setTypeface(fonts.forText(text,bold));p.setTextAlign(Paint.Align.LEFT);return p;}
    private void text(String value,float x,float top,float size,int valueColor,int align,boolean bold){
        textPaint(value,size,valueColor,bold);Paint.FontMetrics fm=p.getFontMetrics();float width=p.measureText(value);if(align==1)x-=width/2;else if(align==2)x-=width;c.drawText(value,x,top-fm.ascent,p);
    }
    public static void centeredText(Canvas canvas,Paint paint,String value,RectF box){
        Rect ink=new Rect();paint.getTextBounds(value,0,value.length(),ink);
        float x=ChartRadarData.centeredOrigin(box.centerX(),ink.left,ink.right);
        float y=ChartRadarData.centeredOrigin(box.centerY(),ink.top,ink.bottom);
        canvas.drawText(value,x,y,paint);
    }
    private void hell(float x,float y){
        RectF box=new RectF(x,y,x+94,y+34);rect(x,y,94,34,0xff290b16);
        color(0xff9c2943);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2.5f);c.drawRect(box,p);
        textPaint("HELL",28,0xffffe6ed,true);centeredText(c,p,"HELL",box);
    }
    private static double angle(int index){return Math.PI/2-index*Math.PI/3;}
    private static float[][] points(double[] radii){float[][] out=new float[6][2];for(int i=0;i<6;i++){double a=angle(i);out[i][0]=(float)(310+Math.cos(a)*radii[i]);out[i][1]=(float)(421-Math.sin(a)*radii[i]);}return out;}
    private static double[] uniform(double value){return new double[]{value,value,value,value,value,value};}
    private void draw(ChartRadarData row,float reveal){
        color(INK);p.setShader(texture);c.drawRect(12,14,608,1060,p);p.setShader(null);
        path(new float[][]{{0,0},{410,0},{345,18},{0,18}},0xff273cb7,0,true);
        path(new float[][]{{554,0},{586,0},{620,57},{620,111}},0xfff29f4b,0,true);
        path(new float[][]{{0,620},{14,645},{14,718},{0,693}},0xfff29f4b,0,true);
        text("NOTES RADAR",34,28,30,INK,0,true);
        String title=row.title;float titleSize=32;textPaint(title,titleSize,INK,false);
        while(p.measureText(title)>552&&titleSize>20){titleSize--;textPaint(title,titleSize,INK,false);}
        while(title.length()>1&&p.measureText(title)>552){title=title.substring(0,title.length()-1);textPaint(title+"…",titleSize,INK,false);}
        if(!title.equals(row.title))title+="…";
        text(title,34,78,titleSize,INK,0,false);
        text("Lv."+shortNumber(row.level)+" / "+row.keys+"K / CID "+row.cid,35,130,16,MUTED,0,false);
        text("等级框 "+shortNumber(row.frame),586,132,14,PINK,2,true);
        float[][] boundary=points(uniform(116));path(boundary,0xff02040b,0,true);
        path(points(uniform(58)),GRID,1,false);path(boundary,0xfff0f5ff,2.4f,false);
        for(int i=0;i<6;i++){
            double a=angle(i);float x=(float)(310+Math.cos(a)*249),y=(float)(421-Math.sin(a)*249)-10;
            line(310,421,boundary[i][0],boundary[i][1],0x66dde6ff,1.1f,false);
            line(boundary[i][0],boundary[i][1],(float)(310+Math.cos(a)*224),(float)(421-Math.sin(a)*224),GRID,1,true);
            if(row.hell(i)){
                textPaint(ChartRadarData.LABELS[i],21,COLORS[i],true);float width=p.measureText(ChartRadarData.LABELS[i]);
                x-=Math.max(0,x+width/2+8+94-606);if(i==1)y-=14;
                text(ChartRadarData.LABELS[i],x,y,21,COLORS[i],1,true);hell(x+width/2+8,y-6);
            }else text(ChartRadarData.LABELS[i],x,y,21,COLORS[i],1,true);
        }
        double[] radii=new double[6];for(int i=0;i<6;i++)radii[i]=row.radius(i,reveal);
        float[][] polygon=points(radii);path(polygon,0x60fb326d,0,true);path(polygon,0x15ff3f77,10,false);path(polygon,0xffff709d,2,false);
        for(int i=0;i<6;i++){
            double a=angle(i);float x=polygon[i][0],y=polygon[i][1];int valueColor=row.hell(i)?0xffffe09d:PINK;
            if(row.hell(i)){dot(x,y,13,0x18ffe09d);dot(x,y,8,0x1cffe09d);}dot(x,y,3.8f,valueColor);
            if(reveal>=.999f&&row.values[i]/row.frame*116>208){float ux=(float)Math.cos(a),uy=-(float)Math.sin(a);float tx=x+ux*11,ty=y+uy*11;line(x-ux*7-uy*5,y-uy*7+ux*5,tx,ty,valueColor,2,false);line(tx,ty,x-ux*7+uy*5,y-uy*7-ux*5,valueColor,2,false);}
            if(reveal>=.999f&&row.hell(i))text(row.number(i),(float)(x+Math.cos(a)*15),(float)(y-Math.sin(a)*15)-15,18,valueColor,1,true);
        }
        dot(38,705,3,PINK);text("外框 "+shortNumber(row.frame),49,692,14,MUTED,0,false);
        for(int i=0;i<6;i++){
            float x=34+(i%2)*284,y=738+(i/2)*88;rect(x,y,268,76,0xff111729);rect(x,y,3,76,COLORS[i]);
            text(ChartRadarData.LABELS[i],x+16,y+8,15,COLORS[i],0,true);
            text(row.number(i),x+16,y+29,32,row.hell(i)?0xffffdc98:INK,0,true);
        }
        line(34,1022,586,1022,GRID,1,false);text("谱面属性 · 等级参考框",34,1038,13,MUTED,0,false);text("MALODY LINK",586,1037,14,MUTED,2,false);
    }
    private static String shortNumber(double value){return value==Math.rint(value)?Integer.toString((int)value):Double.toString(value);}
}
