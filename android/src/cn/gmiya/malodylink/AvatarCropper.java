package cn.gmiya.malodylink;

import android.app.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import java.io.InputStream;
import java.util.function.Consumer;

/** Local square crop. Nothing is written until the user confirms. */
final class AvatarCropper {
    static void open(Activity a,Uri uri,Consumer<Bitmap> done){new Thread(()->{try(InputStream in=a.getContentResolver().openInputStream(uri)){Bitmap source=LocalPlayerProfile.decodeAvatar(in);a.runOnUiThread(()->{if(a.isDestroyed()||a.isFinishing()){source.recycle();return;}edit(a,source,done);});}catch(Exception e){a.runOnUiThread(()->{if(!a.isDestroyed())new AlertDialog.Builder(a).setMessage("无法读取图片，请重新选择").setPositiveButton("知道了",null).show();});}}).start();}
    static void edit(Activity a,Bitmap source,Consumer<Bitmap> done){
        CropView crop=new CropView(a,source);LinearLayout body=AppUi.column(a,16);body.addView(AppUi.text(a,"拖动调整位置，双指缩放；仅保留方框内的区域。",14,AppUi.MUTED,false));body.addView(crop,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("裁剪头像").setView(body).setNegativeButton("取消",null).setNeutralButton("重置",null).setPositiveButton("使用头像",null).create();
        dialog.setOnShowListener(v->{dialog.getButton(-3).setOnClickListener(w->{crop.zoom=1;crop.dx=crop.dy=0;crop.invalidate();});dialog.getButton(-1).setOnClickListener(w->{Bitmap result=crop.result();dialog.dismiss();done.accept(result);});});dialog.setOnDismissListener(v->source.recycle());dialog.show();
    }
    private static final class CropView extends View {
        final Bitmap source;final Paint paint=new Paint(3);final ScaleGestureDetector pinch;float zoom=1,dx,dy,lastX,lastY;final RectF box=new RectF();
        CropView(Activity a,Bitmap source){super(a);this.source=source;setContentDescription("方形头像裁剪区域，拖动图片并双指缩放");pinch=new ScaleGestureDetector(a,new ScaleGestureDetector.SimpleOnScaleGestureListener(){public boolean onScale(ScaleGestureDetector d){float next=Math.max(1,Math.min(6,zoom*d.getScaleFactor())),ratio=next/zoom;dx=(d.getFocusX()-getWidth()/2f)*(1-ratio)+dx*ratio;dy=(d.getFocusY()-getHeight()/2f)*(1-ratio)+dy*ratio;zoom=next;clamp();invalidate();return true;}});}
        protected void onMeasure(int w,int h){int width=MeasureSpec.getSize(w);setMeasuredDimension(width,Math.min(width,AppUi.dp(getContext(),320)));}
        protected void onSizeChanged(int w,int h,int ow,int oh){float side=Math.min(w,h)*.88f;box.set((w-side)/2,(h-side)/2,(w+side)/2,(h+side)/2);clamp();}
        float scale(){return Math.max(box.width()/source.getWidth(),box.height()/source.getHeight())*zoom;}
        void clamp(){float s=scale(),mx=Math.max(0,(source.getWidth()*s-box.width())/2),my=Math.max(0,(source.getHeight()*s-box.height())/2);dx=Math.max(-mx,Math.min(mx,dx));dy=Math.max(-my,Math.min(my,dy));}
        void image(Canvas c){paint.setColor(Color.WHITE);float s=scale();c.drawBitmap(source,null,new RectF(getWidth()/2f+dx-source.getWidth()*s/2,getHeight()/2f+dy-source.getHeight()*s/2,getWidth()/2f+dx+source.getWidth()*s/2,getHeight()/2f+dy+source.getHeight()*s/2),paint);}
        protected void onDraw(Canvas c){c.drawColor(0xff172b3a);image(c);paint.setColor(0xaa000000);Path mask=new Path();mask.setFillType(Path.FillType.EVEN_ODD);mask.addRect(0,0,getWidth(),getHeight(),Path.Direction.CW);mask.addRect(box,Path.Direction.CW);c.drawPath(mask,paint);paint.setColor(Color.WHITE);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(AppUi.dp(getContext(),1));c.drawRect(box,paint);paint.setColor(0x88ffffff);for(int i=1;i<3;i++){c.drawLine(box.left+box.width()*i/3,box.top,box.left+box.width()*i/3,box.bottom,paint);c.drawLine(box.left,box.top+box.height()*i/3,box.right,box.top+box.height()*i/3,paint);}paint.setStyle(Paint.Style.FILL);}
        public boolean onTouchEvent(MotionEvent e){pinch.onTouchEvent(e);if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&e.getPointerCount()==1&&!pinch.isInProgress()){dx+=e.getX()-lastX;dy+=e.getY()-lastY;clamp();invalidate();}lastX=e.getX();lastY=e.getY();return true;}
        Bitmap result(){clamp();Bitmap out=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(out);c.scale(512/box.width(),512/box.height());c.translate(-box.left,-box.top);image(c);return out;}
    }
}
