package cn.gmiya.malodylink;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/** Draw against the viewport itself: bitmap density and scroll measurement cannot crop the fit view. */
public final class PosterPreviewView extends View {
    private Bitmap bitmap;
    private float zoom=1,panX,panY,lastX,lastY;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final ScaleGestureDetector pinch;
    private final GestureDetector gestures;
    public PosterPreviewView(Context c){super(c);
        pinch=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){zoom=Math.max(1,Math.min(5,zoom*d.getScaleFactor()));clamp();invalidate();return true;}
        });
        gestures=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onDoubleTap(MotionEvent e){toggleZoom();return true;}
        });
    }
    public void setBitmap(Bitmap b){bitmap=b;zoom=1;panX=panY=0;invalidate();}
    public void toggleZoom(){zoom=zoom>1?1:2.5f;panX=panY=0;invalidate();}
    public RectF imageBounds(){
        if(bitmap==null)return new RectF();
        float s=Math.min(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight())*zoom;
        float w=bitmap.getWidth()*s,h=bitmap.getHeight()*s;
        return new RectF((getWidth()-w)/2+panX,(getHeight()-h)/2+panY,(getWidth()+w)/2+panX,(getHeight()+h)/2+panY);
    }
    private void clamp(){RectF r=imageBounds();float mx=Math.max(0,(r.width()-getWidth())/2),my=Math.max(0,(r.height()-getHeight())/2);panX=Math.max(-mx,Math.min(mx,panX));panY=Math.max(-my,Math.min(my,panY));}
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){clamp();}
    @Override protected void onDraw(Canvas c){super.onDraw(c);if(bitmap!=null&&!bitmap.isRecycled())c.drawBitmap(bitmap,null,imageBounds(),paint);}
    @Override public boolean onTouchEvent(MotionEvent e){
        pinch.onTouchEvent(e);gestures.onTouchEvent(e);
        if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&e.getPointerCount()==1&&!pinch.isInProgress()&&zoom>1){panX+=e.getX()-lastX;panY+=e.getY()-lastY;clamp();invalidate();}
        lastX=e.getX();lastY=e.getY();if(e.getActionMasked()==MotionEvent.ACTION_UP)performClick();return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
