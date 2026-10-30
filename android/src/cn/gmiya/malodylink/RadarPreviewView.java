package cn.gmiya.malodylink;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.view.animation.DecelerateInterpolator;

/** Animates only data geometry; keeps the frame, text and numeric values fixed. */
public final class RadarPreviewView extends View {
    private final RadarPoster poster;
    private final ScaleGestureDetector pinch;
    private final GestureDetector gestures;
    private ChartRadarData data;
    private ValueAnimator animator;
    private float reveal=1,zoom=1,panX,panY,lastX,lastY;
    public RadarPreviewView(Context context){super(context);poster=new RadarPoster(context);
        pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){zoom=Math.max(1,Math.min(8,zoom*d.getScaleFactor()));clamp();invalidate();return true;}});
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){@Override public boolean onDown(MotionEvent e){return true;}@Override public boolean onDoubleTap(MotionEvent e){toggleZoom();return true;}});
    }
    public void setData(ChartRadarData value){data=value;zoom=1;panX=panY=0;reveal=0;setContentDescription(value.description()+"。双指缩放，拖动查看");if(isAttachedToWindow())replay();invalidate();}
    public void replay(){
        if(animator!=null)animator.cancel();
        if(data==null)return;
        if(!ValueAnimator.areAnimatorsEnabled()){reveal=1;invalidate();return;}
        animator=ValueAnimator.ofFloat(0,1);animator.setDuration(700);animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(v->{reveal=(float)v.getAnimatedValue();invalidate();});animator.start();
    }
    public float animationProgress(){return reveal;}
    public void toggleZoom(){zoom=zoom>1?1:2.5f;panX=panY=0;invalidate();}
    public RectF imageBounds(){float scale=Math.min(getWidth()/(float)RadarPoster.WIDTH,getHeight()/(float)RadarPoster.HEIGHT)*zoom;float w=RadarPoster.WIDTH*scale,h=RadarPoster.HEIGHT*scale;return new RectF((getWidth()-w)/2+panX,(getHeight()-h)/2+panY,(getWidth()+w)/2+panX,(getHeight()+h)/2+panY);}
    private void clamp(){RectF r=imageBounds();float mx=Math.max(0,(r.width()-getWidth())/2),my=Math.max(0,(r.height()-getHeight())/2);panX=Math.max(-mx,Math.min(mx,panX));panY=Math.max(-my,Math.min(my,panY));}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){clamp();}
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(data!=null)replay();}
    @Override protected void onDetachedFromWindow(){if(animator!=null)animator.cancel();super.onDetachedFromWindow();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(data==null)return;RectF r=imageBounds();if(r.width()<=0||r.height()<=0)return;int count=canvas.save();canvas.translate(r.left,r.top);canvas.scale(r.width()/RadarPoster.WIDTH,r.height()/RadarPoster.HEIGHT);poster.draw(canvas,data,reveal);canvas.restoreToCount(count);}
    @Override public boolean onTouchEvent(MotionEvent e){pinch.onTouchEvent(e);gestures.onTouchEvent(e);if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&e.getPointerCount()==1&&!pinch.isInProgress()&&zoom>1){panX+=e.getX()-lastX;panY+=e.getY()-lastY;clamp();invalidate();}lastX=e.getX();lastY=e.getY();if(e.getActionMasked()==MotionEvent.ACTION_UP)performClick();return true;}
    @Override public boolean performClick(){super.performClick();return true;}
}
