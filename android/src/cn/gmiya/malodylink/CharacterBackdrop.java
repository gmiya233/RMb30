package cn.gmiya.malodylink;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/** Original transparent character artwork, faded behind native page content. */
public final class CharacterBackdrop extends Drawable {
    private static final String[] ASSETS={"home","b30","ranking","songs","settings"};
    private static final Map<String,Bitmap> CACHE=new HashMap<>();
    private final Context context;
    private final Bitmap image;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Paint mask=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect source=new Rect();
    private final RectF destination=new RectF();
    private Shader horizontal,vertical;
    private int artAlpha=48;

    public CharacterBackdrop(Context context,int page){
        this.context=context.getApplicationContext();
        String name=ASSETS[Math.max(0,Math.min(ASSETS.length-1,page))];
        image=load(this.context,name);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        if(image!=null)source.set(0,0,image.getWidth(),Math.min(image.getHeight(),Math.round(image.getWidth()*1.18f)));
    }

    private static synchronized Bitmap load(Context context,String name){
        if(!CACHE.containsKey(name)){
            try(InputStream stream=context.getAssets().open("characters/"+name+".png")){
                CACHE.put(name,BitmapFactory.decodeStream(stream));
            }catch(Exception missing){CACHE.put(name,null);}
        }
        return CACHE.get(name);
    }

    @Override protected void onBoundsChange(Rect bounds){
        if(image==null||bounds.isEmpty())return;
        float width=Math.min(AppUi.dp(context,232),bounds.width()*.43f);
        float right=bounds.right-AppUi.dp(context,4);
        float top=bounds.top+AppUi.dp(context,4);
        destination.set(right-width,top,right,top+width*source.height()/source.width());
        horizontal=new LinearGradient(destination.left,0,destination.right,0,
                new int[]{Color.TRANSPARENT,Color.WHITE,Color.WHITE},new float[]{0,.22f,1},Shader.TileMode.CLAMP);
        vertical=new LinearGradient(0,destination.top,0,destination.bottom,
                new int[]{Color.WHITE,Color.WHITE,Color.TRANSPARENT},new float[]{0,.52f,1},Shader.TileMode.CLAMP);
    }

    @Override public void draw(Canvas canvas){
        paint.setShader(null);paint.setColor(AppUi.BG);paint.setAlpha(255);
        canvas.drawRect(getBounds(),paint);
        if(image==null||destination.isEmpty())return;
        int layer=canvas.saveLayerAlpha(destination,artAlpha);
        canvas.drawBitmap(image,source,destination,paint);
        mask.setShader(horizontal);canvas.drawRect(destination,mask);
        mask.setShader(vertical);canvas.drawRect(destination,mask);
        canvas.restoreToCount(layer);
    }

    @Override public void setAlpha(int alpha){artAlpha=Math.max(0,Math.min(255,alpha));invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){/* Keep the original artwork colours. */}
    @Override public int getOpacity(){return PixelFormat.OPAQUE;}
}
