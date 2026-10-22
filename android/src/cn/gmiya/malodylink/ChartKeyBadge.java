package cn.gmiya.malodylink;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.util.*;

/** Original user-supplied RM UI badges, shared by native screens and exported posters. */
public final class ChartKeyBadge {
    private static final Map<Integer,Bitmap> images=new HashMap<>();
    private static synchronized Bitmap image(Context context,int keys){
        if(keys<4||keys>6)return null;
        if(!images.containsKey(keys)){try(java.io.InputStream in=context.getAssets().open("rm-ui/keys_"+keys+".png")){images.put(keys,BitmapFactory.decodeStream(in));}catch(Exception unavailable){images.put(keys,null);}}
        return images.get(keys);
    }
    public static View view(Context context,JSONObject chart,int height){
        int count=ChartKeys.count(chart);Bitmap bitmap=image(context,count);
        if(bitmap==null)return AppUi.text(context,ChartKeys.label(chart),12,AppUi.MUTED,false);
        ImageView view=new ImageView(context);view.setImageBitmap(bitmap);view.setScaleType(ImageView.ScaleType.FIT_CENTER);view.setContentDescription(count+"键");
        view.setLayoutParams(new LinearLayout.LayoutParams(AppUi.dp(context,height*2),AppUi.dp(context,height)));return view;
    }
    public static void draw(Canvas canvas,Context context,int keys,RectF bounds){
        if(keys<=0)return;Bitmap bitmap=image(context,keys);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        if(bitmap!=null){canvas.drawBitmap(bitmap,null,bounds,paint);return;}
        paint.setColor(0xff14334f);canvas.drawRoundRect(bounds,5,5,paint);paint.setColor(Color.WHITE);paint.setTextSize(bounds.height()*.65f);paint.setTextAlign(Paint.Align.CENTER);Paint.FontMetrics fm=paint.getFontMetrics();canvas.drawText(keys+"K",bounds.centerX(),bounds.centerY()-(fm.ascent+fm.descent)/2,paint);
    }
}
