package cn.gmiya.malodylink;
import android.content.Context;import android.graphics.*;import android.view.View;import java.util.*;
final class GameplayView extends View {
 Bitmap background;GameplayChart chart;double time,approach=GameplayMotion.approachForSpeed(2);boolean followScroll=false;private final Paint p=new Paint(3);private final Path path=new Path();private final Map<String,Bitmap> sprites=new HashMap<>();
 GameplayView(Context c){super(c);setContentDescription("AUTO自动游玩预览，不记录成绩");}
 double distance(double at){return followScroll?chart.travel(at)-chart.travel(time):at-time;}
 float y(double at){return (float)GameplayMotion.y(distance(at),approach,getHeight());}
 float half(float y){return (float)GameplayMotion.halfWidth(y,getWidth(),getHeight());}
 float x(double value,float y){return getWidth()/2f+(float)(value/128-1)*half(y);}
 void color(int c){p.setColor(c);p.setStyle(Paint.Style.FILL);}
 void text(Canvas c,String s,float size,float y,int col){color(col);p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(size);p.setTextAlign(Paint.Align.CENTER);c.drawText(s,getWidth()/2f,y,p);}
 Bitmap image(String name){if(!sprites.containsKey(name))try(java.io.InputStream in=getContext().getAssets().open("gameplay-skin/"+name+".png")){sprites.put(name,BitmapFactory.decodeStream(in));}catch(Exception ignored){sprites.put(name,null);}return sprites.get(name);}
 // The supplied notes have no baked lane skew; project their corners onto the track.
 final Matrix textureMatrix=new Matrix();final float[] textureSrc=new float[8],textureDst=new float[8];
 void quad(Canvas c,Bitmap b,float l0,float r0,float y0,float l1,float r1,float y1){if(b==null||Math.abs(y1-y0)<.01f||(Math.abs(r0-l0)<.01f&&Math.abs(r1-l1)<.01f))return;textureSrc[0]=0;textureSrc[1]=0;textureSrc[2]=b.getWidth();textureSrc[3]=0;textureSrc[4]=b.getWidth();textureSrc[5]=b.getHeight();textureSrc[6]=0;textureSrc[7]=b.getHeight();textureDst[0]=l0;textureDst[1]=y0;textureDst[2]=r0;textureDst[3]=y0;textureDst[4]=r1;textureDst[5]=y1;textureDst[6]=l1;textureDst[7]=y1;textureMatrix.setPolyToPoly(textureSrc,0,textureDst,0,4);color(0xffffffff);c.drawBitmap(b,textureMatrix,p);}
 void marker(Canvas c,String asset,float cx,float cy,float w,float h){Bitmap b=image("rm-notes/"+asset);if(b!=null){color(0xffffffff);c.drawBitmap(b,null,new RectF(cx-w/2,cy-h/2,cx+w/2,cy+h/2),p);}}
 float widthAt(float yy){return (float)GameplayMotion.noteWidth(chart.keys,yy,getWidth(),getHeight());}
 void segment(Canvas c,double a,double ax,double b,double bx){
  if(b<a)return;float top=(float)(getHeight()*GameplayMotion.TOP),bottom=(float)(getHeight()*GameplayMotion.JUDGE);
  float ya=Math.max(top,Math.min(bottom,y(a))),yb=Math.max(top,Math.min(bottom,y(b)));
  if(a==b){float left=Math.min(x(ax,ya),x(bx,ya)),right=Math.max(x(ax,ya),x(bx,ya)),h=widthAt(ya)*.13f;Bitmap body=image("rm-notes/holdbodyhorizontal");quad(c,body,left,right,ya-h/2,left,right,ya+h/2);}
  else {float wa=widthAt(ya)*.14f,wb=widthAt(yb)*.14f;Bitmap body=image("rm-notes/holdbodyvertical");quad(c,body,x(bx,yb)-wb/2,x(bx,yb)+wb/2,yb,x(ax,ya)-wa/2,x(ax,ya)+wa/2,ya);}
 }
 void head(Canvas c,GameplayChart.Note n,double at,double pos,boolean hold){float yy=y(at);if(yy<getHeight()*GameplayMotion.TOP||yy>getHeight()*(GameplayMotion.JUDGE+.03))return;float h=(float)GameplayMotion.halfNoteHeight(chart.keys,yy,getWidth(),getHeight()),y0=yy-h,y1=yy+h;float w0=widthAt(y0),w1=widthAt(y1);Bitmap b=image("rm-notes/"+(hold?"holdhead":"note"));quad(c,b,x(pos,y0)-w0/2,x(pos,y0)+w0/2,y0,x(pos,y1)-w1/2,x(pos,y1)+w1/2,y1);}
 @Override protected void onDraw(Canvas c){super.onDraw(c);c.drawColor(0xff06111b);if(background!=null){float scale=Math.max(getWidth()/(float)background.getWidth(),getHeight()/(float)background.getHeight());float w=background.getWidth()*scale,h=background.getHeight()*scale;color(0xffffffff);c.drawBitmap(background,null,new RectF((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2),p);color(0x88000000);c.drawRect(0,0,getWidth(),getHeight(),p);}if(chart==null)return;float top=(float)(getHeight()*GameplayMotion.TOP),bottom=(float)(getHeight()*GameplayMotion.JUDGE);
 Bitmap track=image("1712474142416");if(track!=null){float sy=getHeight()*.735f/824f;float w=getWidth()*.935f;float left=(getWidth()-w)/2;color(0xffffffff);c.drawBitmap(track,null,new RectF(left,-50*sy,left+w,1150*sy),p);}
 for(int lane=0;lane<=chart.keys;lane++){double value=lane*256.0/chart.keys;p.setColor(0x554d545f);p.setStrokeWidth(1);c.drawLine(x(value,top),top,x(value,bottom),bottom,p);}
 for(int lane=0;lane<chart.keys;lane++){double a=lane*256.0/chart.keys,b=(lane+1)*256.0/chart.keys;float lower=(float)(getHeight()*GameplayMotion.BOTTOM);path.reset();path.moveTo(x(a,bottom)+3,bottom+8);path.lineTo(x(b,bottom)-3,bottom+8);path.lineTo(x(b,lower)-4,lower);path.lineTo(x(a,lower)+4,lower);path.close();p.setShader(new LinearGradient(0,bottom,0,lower,new int[]{0xff574a42,0xff1d252c,0xff071b25},null,Shader.TileMode.CLAMP));c.drawPath(path,p);p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(0xffa18a71);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);p.setColor(0xff34728b);p.setStrokeWidth(3);c.drawLine(x(a,lower)+4,lower,x(b,lower)-4,lower,p);}
 p.setColor(0xffbea58a);p.setStrokeWidth(4);c.drawLine(x(0,bottom),bottom,x(256,bottom),bottom,p);
 int combo=0;boolean hit=false;boolean[] glow=new boolean[chart.keys];c.save();c.clipRect(0,top,getWidth(),bottom+8);
 for(GameplayChart.Note n:chart.notes){if(n.at<=time)combo++;if(n.end<time-.14||Math.min(distance(n.at),distance(n.end))>approach)continue;if(n.at<=time&&time-n.at<.14){hit=true;glow[Math.min(chart.keys-1,(int)(n.x*chart.keys/256))]=true;}
 if(!n.points.isEmpty()){double prev=n.at,px=n.x,pos=n.x;for(double[] pt:n.points){if(pt[0]>=time&&distance(prev)<=approach){segment(c,Math.max(prev,time),px,pt[0],px);if(distance(pt[0])<=approach&&pt[1]!=px){segment(c,pt[0],px,pt[0],pt[1]);float py=y(pt[0]),size=widthAt(py)*.37f;marker(c,"node",x(px,py),py,size,size);marker(c,"node",x(pt[1],py),py,size,size);}}if(pt[0]<=time)pos=pt[1];prev=pt[0];px=pt[1];}head(c,n,Math.max(n.at,time),pos,true);if(n.at<=time&&n.end>=time)glow[Math.min(chart.keys-1,(int)(pos*chart.keys/256))]=true;}
 else if(n.at>=time)head(c,n,n.at,n.x,n.dir!=0);
 int direction=n.points.isEmpty()?n.dir:n.tail;double at=n.points.isEmpty()?n.at:n.end,pos=n.points.isEmpty()?n.x:n.points.get(n.points.size()-1)[1];
 if(n.end>=time&&n.end>n.at&&distance(n.end)<=approach&&(direction&10)==0){float yy=y(n.end),w=widthAt(yy)*.42f;marker(c,"holdend",x(pos,yy),yy,w,w*80/128);}
 if((direction&10)!=0&&at>=time&&distance(at)<=approach){float yy=y(at),xx=x(pos,yy),nw=widthAt(yy),sign=(direction&2)!=0?1:-1,len=nw*.83f,h=nw*.13f;Bitmap body=image("rm-notes/holdbodyhorizontal");float left=Math.min(xx,xx+sign*len),right=Math.max(xx,xx+sign*len);quad(c,body,left,right,yy-h/2,left,right,yy+h/2);marker(c,sign>0?"arrowright":"arrowleft",xx+sign*len,yy,nw*.67f,nw*.67f*182/148);}}

 c.restore();for(int lane=0;lane<chart.keys;lane++){float xx=x((lane+.5)*256/chart.keys,bottom);color(glow[lane]?0xff8cffff:0xff3f657a);c.drawRoundRect(xx-half(bottom)/chart.keys*.86f,bottom-4,xx+half(bottom)/chart.keys*.86f,bottom+9,4,4,p);if(glow[lane]){color(0x887affff);c.drawCircle(xx,bottom,22,p);}}
 text(c,"AUTO PREVIEW",getHeight()*.025f,getHeight()*.96f,0xffc3d9dd);if(combo>0){p.setShader(new LinearGradient(0,getHeight()*.29f,0,getHeight()*.44f,0xfffbb4e0,0xffb879ba,Shader.TileMode.CLAMP));text(c,Integer.toString(combo),getHeight()*.13f,getHeight()*.43f,0xffffffff);p.setShader(null);}if(hit){p.setShader(new LinearGradient(0,getHeight()*.17f,0,getHeight()*.29f,0xffffffff,0xff65d6aa,Shader.TileMode.CLAMP));text(c,"很棒",getHeight()*.085f,getHeight()*.275f,0xffffffff);p.setShader(null);}
 }
 void release(){if(background!=null){background.recycle();background=null;}for(Bitmap b:sprites.values())if(b!=null)b.recycle();sprites.clear();}
}
