package cn.gmiya.malodylink;

import android.content.Context;
import android.graphics.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Native canvas poster: identical drawing code for preview and exported PNG. */
public final class ScorePoster {
    public static final int WIDTH=1500,HEIGHT=1664;
    private static final int NAVY=0xff14334f,CYAN=0xff08acbf,PINK=0xffb742aa;
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Map<Integer,Bitmap> jackets=new HashMap<>();
    private final Context context;
    private Canvas c;
    public ScorePoster(Context context){this.context=context;}
    private void rect(float x,float y,float w,float h,int color,float radius){p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawRoundRect(x,y,x+w,y+h,radius,radius,p);}
    private void text(String text,float x,float y,float size,int color,boolean bold){p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextSize(size);p.setTypeface(bold?Typeface.create("sans-serif",Typeface.BOLD):Typeface.create("sans-serif",Typeface.NORMAL));c.drawText(text,x,y,p);}
    private String fit(String text,float size,float width){p.setTextSize(size);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));if(p.measureText(text)<=width)return text;int end=text.length();while(end>0&&p.measureText(text.substring(0,end)+"…")>width)end--;if(end>0&&Character.isHighSurrogate(text.charAt(end-1)))end--;return text.substring(0,end)+"…";}
    private void polygon(float[] xy,int color){p.setShader(null);p.setColor(color);p.setStyle(Paint.Style.FILL);Path path=new Path();path.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)path.lineTo(xy[i],xy[i+1]);path.close();c.drawPath(path,p);}
    private Bitmap jacket(int cid){if(jackets.containsKey(cid))return jackets.get(cid);Bitmap bitmap=null;
        try{File file=new File(context.getFilesDir(),"jackets/"+cid+".img");InputStream in=file.exists()?new FileInputStream(file):context.getAssets().open("jackets/"+cid+".img");byte[] data=Engine.read(in,8000000);BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(data,0,data.length,bounds);BitmapFactory.Options o=new BitmapFactory.Options();o.inSampleSize=Math.max(1,Math.max(bounds.outWidth,bounds.outHeight)/256);bitmap=BitmapFactory.decodeByteArray(data,0,data.length,o);}catch(Exception ignored){}
        jackets.put(cid,bitmap);return bitmap;
    }
    private void cover(int cid,float x,float y,float side,int accent){Bitmap b=jacket(cid);if(b==null){rect(x,y,side,side,0xffe3f2f5,8);text("♪",x+side*.25f,y+side*.76f,side*.65f,accent,true);return;}
        int min=Math.min(b.getWidth(),b.getHeight()),left=(b.getWidth()-min)/2,top=(b.getHeight()-min)/2;Path clip=new Path();clip.addRoundRect(new RectF(x,y,x+side,y+side),7,7,Path.Direction.CW);c.save();c.clipPath(clip);p.setColor(Color.WHITE);c.drawBitmap(b,new Rect(left,top,left+min,top+min),new RectF(x,y,x+side,y+side),p);c.restore();}
    private void logo(){
        float x=1010,y=117;p.setTextSize(79);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD_ITALIC));p.setStyle(Paint.Style.STROKE);p.setStrokeJoin(Paint.Join.ROUND);p.setStrokeWidth(15);p.setColor(0x77ffffff);c.drawText("节奏大师",x,y,p);
        p.setStrokeWidth(9);p.setColor(0xff325e90);c.drawText("节奏大师",x,y,p);p.setStyle(Paint.Style.FILL);p.setShader(new LinearGradient(x,y-85,x,y,new int[]{0xffffffff,0xffedf8ff,0xff99e6fc},null,Shader.TileMode.CLAMP));c.drawText("节奏大师",x,y,p);p.setShader(null);
        text("RHYTHM MASTER",x+18,y+37,25,0xff274e77,true);polygon(new float[]{995,42,1007,74,1041,72,1015,94,1028,127,998,109,971,131,979,96,951,78,985,76},0xffc5ff61);
        polygon(new float[]{1000,163,1423,155,1407,165,1028,180},0xffef4ec0);
    }
    private void header(PosterData.Board b){
        // Player card: local avatar, earned song title, nickname and record summary.
        rect(54,51,900,238,0x24406e96,14);rect(48,44,900,238,0xeaffffff,14);
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(3);p.setColor(0xffca54a2);c.drawRoundRect(48,44,948,282,14,14,p);p.setStyle(Paint.Style.FILL);
        Bitmap avatar=LocalPlayerProfile.avatarBitmap(context);rect(64,60,208,208,0xff214969,10);
        if(avatar!=null){Path crop=new Path();crop.addRoundRect(new RectF(64,60,272,268),10,10,Path.Direction.CW);c.save();c.clipPath(crop);c.drawBitmap(avatar,null,new RectF(64,60,272,268),p);c.restore();avatar.recycle();}
        else{p.setColor(0xff9fe9ed);c.drawCircle(168,130,32,p);c.drawRoundRect(112,172,224,248,36,36,p);}
        rect(288,69,640,60,0xffd9eef1,9);p.setShader(new LinearGradient(0,73,0,124,new int[]{0xffffef9c,0xffffffff,0xffffdf70},null,Shader.TileMode.CLAMP));c.drawRoundRect(292,73,924,125,7,7,p);p.setShader(null);
        String title=b.title==null||b.title.isEmpty()?LocalPlayerProfile.DEFAULT_TITLE:b.title;text(fit(title,29,596),306,109,29,NAVY,true);
        text(fit(b.player,46,620),295,187,46,NAVY,true);
        text("PLAYER SCORE RECORD",297,217,17,0xff5b8797,true);
        text("rating",296,257,25,0xff52758b,false);text(String.format(Locale.ROOT,"%.4f",b.rating()),389,258,34,0xff168c9f,true);
        text("O "+b.old.size()+"/20   N "+b.fresh.size()+"/10",664,256,24,0xff52758b,true);logo();
        rect(1017,192,395,99,0x665b65b4,22);rect(1008,183,395,99,0xffe9dcfa,22);rect(1015,189,381,84,0xf5ffffff,18);
        text("PLAYER RATING",1075,216,20,0xff8065a6,true);text(String.format(Locale.ROOT,"%.4f",b.rating()),1082,261,47,0xff9f459e,true);
        if(b.demo)text("示例数据",793,64,17,0xffa5267c,true);
    }
    private void section(String group,String subtitle,List<PosterData.Entry> entries,int capacity,float y,int accent){
        rect(48,y,1404,57,0xdbffffff,10);rect(48,y,8,57,accent,3);text(group,70,y+40,34,accent,true);
        text(subtitle,183,y+38,23,NAVY,false);double sum=0;for(PosterData.Entry e:entries)if(Double.isFinite(e.converted))sum+=e.converted;text(String.format(Locale.ROOT,"rating %.4f",sum/capacity),955,y+38,27,accent,true);text(entries.size()+" / "+capacity,1330,y+38,24,NAVY,true);
        for(int i=0;i<capacity;i++){float x=48+(i%5)*284,top=y+75+(i/5)*174;tile(i<entries.size()?entries.get(i):null,i+1,x,top,accent);}
    }
    private void tile(PosterData.Entry e,int index,float x,float y,int accent){
        rect(x+3,y+6,268,158,0x203f75a0,9);rect(x,y,268,158,e==null?0xb5ffffff:0xfaffffff,9);
        if(e==null){rect(x+13,y+17,81,81,0xffe0eef2,6);text("♪",x+36,y+75,49,0xffabc9d0,true);text("待收录",x+110,y+52,23,0xff69869a,true);text(String.format(Locale.ROOT,"SLOT %02d",index),x+110,y+82,16,0xff859ca9,false);text("完成游玩后收录最佳成绩",x+14,y+130,18,0xff7d96a6,false);return;}
        cover(e.cid,x+12,y+16,82,accent);rect(x+12,y+16,31,22,accent,4);text(String.format(Locale.ROOT,"%02d",index),x+16,y+33,15,Color.WHITE,true);
        text(fit(e.title,20,150),x+106,y+36,20,NAVY,true);
        double displayLevel=e.displayLevel();
        String level=Double.isFinite(displayLevel)?String.format(Locale.ROOT,"Lv.%s",displayLevel%1==0?Integer.toString((int)displayLevel):Double.toString(displayLevel)):"待定级";
        int sc=ChartDetails.sc(e.difficultyVersion);if(sc>0&&!e.levelOverride)level+=" · SC"+sc;rect(x+106,y+45,148,23,accent,5);text(level,x+113,y+62,16,Color.WHITE,true);
        text(String.format(Locale.ROOT,"%,d",e.score),x+105,y+99,29,0xff17344a,true);
        text(RatingRules.judgeName(e.judge)+(e.manual?"*":""),x+14,y+124,18,accent,true);
        text(String.format(Locale.ROOT,"准度 %.3f%%",e.accuracy),x+87,y+124,18,0xff38536a,false);
        String converted=Double.isFinite(e.converted)?String.format(Locale.ROOT,"%.4f",e.converted):"待定";
        text("rating",x+14,y+149,17,0xff4a6980,false);text(converted,x+82,y+149,22,accent,true);
    }
    public Bitmap render(PosterData.Board board){
        Bitmap bitmap=Bitmap.createBitmap(WIDTH,HEIGHT,Bitmap.Config.ARGB_8888);c=new Canvas(bitmap);
        p.setShader(new LinearGradient(0,0,WIDTH,HEIGHT,new int[]{0xff38dfdf,0xffc6f8ff,0xffe9dcff,0xff75ccff},null,Shader.TileMode.CLAMP));c.drawRect(0,0,WIDTH,HEIGHT,p);p.setShader(null);
        polygon(new float[]{0,315,1500,80,1500,140,0,380},0x80ffffff);polygon(new float[]{0,1100,1500,520,1500,568,0,1160},0x55ffffff);
        polygon(new float[]{0,1415,1500,1130,1500,1171,0,1454},0x70ffffff);polygon(new float[]{0,15,350,0,890,0,0,90},0xff68f4ca);
        polygon(new float[]{1260,0,1500,0,1500,35},0xffc9fa43);polygon(new float[]{0,1513,1500,1430,1500,1442,0,1530},0xffdc56be);
        for(int i=0;i<12;i++){float x=(i*193)%1500,y=340+(i*233)%1050;c.save();c.rotate(18,x,y);rect(x,y,62,62,0x28ffffff,6);c.restore();}
        header(board);section("O20","日常歌曲 · rating 前 20",board.old,20,307,CYAN);
        section("N10","promote 新歌 · rating 前 10",board.fresh,10,1111,PINK);
        text("rating = 折算等级 × 准度系数 × 判定倍率   |   99.5% → 100%   ·   99.8%+ → 101%",49,1570,20,NAVY,false);
        text("低于99.5%取 (准度/99.5)²；99.5%～99.8%线性增长；其后封顶。缺位按0计入30曲均值。",49,1602,18,0xff38536a,false);
        text("SC1–4→12，SC5–13→13，SC14+→15；其他等级按MaV→RM表 · * 手动预览 · 非官方 MM",49,1633,18,0xff38536a,false);
        if(board.demo){c.save();c.rotate(-16,WIDTH/2f,HEIGHT/2f);text("示例数据 / SAMPLE",220,HEIGHT/2f,92,0x22a62c8d,true);c.restore();}
        for(Bitmap b:jackets.values())if(b!=null)b.recycle();jackets.clear();return bitmap;
    }
}
