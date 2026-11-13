"""Offline video export from a normalized gameplay timeline; not a device recording."""
from pathlib import Path
import argparse,bisect,json,math,os,re,subprocess,sys,time
import numpy as np
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/python-libs'))
import imageio_ffmpeg
from PIL import Image,ImageDraw,ImageEnhance,ImageFont,ImageOps

class ScrollTrack:
 def __init__(self,rows):
  events={-3600.:1.}
  if len(rows)>20000:raise ValueError('Too many scroll events')
  for t,v in rows:
   if not math.isfinite(t) or not math.isfinite(v) or not -3600<=t<=36000 or abs(v)>10000:raise ValueError('Invalid scroll event')
   events[float(t)]=float(v)
  self.times=sorted(events);self.values=[events[t] for t in self.times];self.distances=[0.]
  for i in range(1,len(self.times)):
   self.distances.append(self.distances[-1]+(self.times[i]-self.times[i-1])*self.values[i-1])
 def travel(self,t):
  i=max(0,bisect.bisect_right(self.times,t)-1)
  return self.distances[i]+(t-self.times[i])*self.values[i]
 def span(self,a,b):
  values=[self.travel(a),self.travel(b)]
  values.extend(self.distances[i] for i in range(bisect.bisect_right(self.times,a),bisect.bisect_left(self.times,b)))
  return min(values),max(values)

class Stage:
 TOP_Y=8.64
 JUDGE_Y=512.0
 CLIP_BOTTOM=524
 def __init__(self,chart,cover,title,subtitle="LV.13 / SC10"):
  self.chart=chart;self.keys=chart['keys'];self.approach=.588
  self.scroll=ScrollTrack(chart.get('scroll',[]))
  self.assets={n:Image.open(ROOT/f'android/assets/gameplay-skin/rm-notes/{n}.png').convert('RGBA') for n in ('note','holdhead','holdbodyvertical','holdbodyhorizontal','holdend','node','arrowleft','arrowright')}
  regular='C:/Windows/Fonts/msyh.ttc' if Path('C:/Windows/Fonts/msyh.ttc').exists() else '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'
  bold='C:/Windows/Fonts/msyhbd.ttc' if Path('C:/Windows/Fonts/msyhbd.ttc').exists() else '/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc'
  self.font=ImageFont.truetype(regular,19)
  self.large=ImageFont.truetype(bold,68)
  self.judge=ImageFont.truetype(bold,40)
  self.base=ImageEnhance.Brightness(ImageOps.fit(Image.open(cover).convert('RGB'),(1280,576))).enhance(.38)
  track=Image.open(ROOT/'android/assets/gameplay-skin/1712474142416.png').convert('RGBA');sy=576*.735/824;tw=int(1280*.935)
  track=track.resize((tw,int(1200*sy)),Image.Resampling.LANCZOS);self.base.paste(track,((1280-tw)//2,int(-50*sy)),track)
  d=ImageDraw.Draw(self.base)
  for i in range(self.keys+1):d.line((self.x(i/self.keys*256,8.64),8.64,self.x(i/self.keys*256,423.36),423.36),fill='#43464f',width=1)
  for i in range(self.keys):
   a=i/self.keys*256;b=(i+1)/self.keys*256
   d.polygon([(self.x(a,428)+3,428),(self.x(b,428)-3,428),(self.x(b,self.JUDGE_Y)-3,self.JUDGE_Y),(self.x(a,self.JUDGE_Y)+3,self.JUDGE_Y)],fill='#252c34',outline='#a48d72')
   d.line((self.x(a,self.JUDGE_Y-2)+4,self.JUDGE_Y-2,self.x(b,self.JUDGE_Y-2)-4,self.JUDGE_Y-2),fill='#387289',width=2)
  d.line((self.x(0,self.JUDGE_Y),self.JUDGE_Y,self.x(256,self.JUDGE_Y),self.JUDGE_Y),fill='#d8b590',width=3)
  d.text((22,16),title,font=self.font,fill='#e8fcff',stroke_width=1,stroke_fill='#07121c')
  d.text((22,44),f'{self.keys}K · {subtitle} · AUTO',font=self.font,fill='#b5e8ed')
  if chart.get('effects'):d.text((22,72),'含尚未还原的非流速效果',font=self.font,fill='#ffda98')
  d.text((22,548),'Malody Link · 谱面自动游玩预览',font=self.font,fill='#b5e8ed')
  self.notes=sorted(chart['notes'],key=lambda n:n[0]);self.starts=[n[0] for n in self.notes]
  self.ends=[max([n[0]]+[p[0] for p in n[5]]) for n in self.notes]
  self.completed=sorted(self.ends)
  spans=[self.scroll.span(a,b) for a,b in zip(self.starts,self.ends)]
  self.low=np.array([v[0] for v in spans]);self.high=np.array([v[1] for v in spans]);self.end_array=np.array(self.ends)
 def y(self,at,now):
  distance=self.scroll.travel(at)-self.scroll.travel(now)
  p=1-.5*max(-4*self.approach,min(4*self.approach,distance))/self.approach
  return self.TOP_Y+(self.JUDGE_Y-self.TOP_Y)*(p**3-.125)/.875
 def half(self,y):return 400*(.20666666666666667+(1-.20666666666666667)*(y/576-.015)/.72)
 def x(self,pos,y):return 640+(pos/128-1)*self.half(y)
 def width(self,y):return 2*self.half(y)*({4:406,5:337.8,6:290}[self.keys])/1680
 def height(self,y):return max(2,self.width(y)*116/({4:406,5:337.8,6:290}[self.keys])/2)
 def quad(self,im,key,pts):
  left=max(0,int(min(p[0] for p in pts))-2);top=max(8,int(min(p[1] for p in pts))-2)
  right=min(1280,int(max(p[0] for p in pts))+3);bottom=min(self.CLIP_BOTTOM,int(max(p[1] for p in pts))+3)
  if right<=left or bottom<=top:return
  sprite=self.assets[key];A=[];B=[]
  for (dx,dy),(sx,sy) in zip(pts,[(0,0),(sprite.width,0),(sprite.width,sprite.height),(0,sprite.height)]):
   dx-=left;dy-=top
   A.extend([[dx,dy,1,0,0,0,-sx*dx,-sx*dy],[0,0,0,dx,dy,1,-sy*dx,-sy*dy]]);B.extend([sx,sy])
  try:coeff=np.linalg.solve(np.array(A),np.array(B))
  except np.linalg.LinAlgError:return
  patch=sprite.transform((right-left,bottom-top),Image.Transform.PERSPECTIVE,coeff,Image.Resampling.BICUBIC)
  im.paste(patch,(left,top),patch)
 def marker(self,im,key,pos,y,w,h):
  sprite=self.assets[key].resize((max(2,int(w)),max(2,int(h))),Image.Resampling.BICUBIC)
  # Clip all note parts to the same stage region as the Android view.
  l=int(self.x(pos,y)-w/2);t=int(y-h/2);a=max(0,l);b=max(8,t);r=min(1280,l+sprite.width);bot=min(self.CLIP_BOTTOM,t+sprite.height)
  if r>a and bot>b:
   patch=sprite.crop((a-l,b-t,r-l,bot-t));im.paste(patch,(a,b),patch)
 def head(self,im,pos,y,hold):
  if y<self.TOP_Y or y>self.JUDGE_Y+16:return
  h=self.height(y);a=y-h;b=y+h;wa=self.width(a);wb=self.width(b)
  self.quad(im,'holdhead' if hold else 'note',[(self.x(pos,a)-wa/2,a),(self.x(pos,a)+wa/2,a),(self.x(pos,b)+wb/2,b),(self.x(pos,b)-wb/2,b)])
 def segment(self,im,a,ax,b,bx,now):
  cuts=[a]+self.scroll.times[bisect.bisect_right(self.scroll.times,a):bisect.bisect_left(self.scroll.times,b)]+([b] if b>a else [])
  if len(cuts)>2 and ax==bx:
   for left,right in zip(cuts,cuts[1:]):self._segment(im,left,ax,right,bx,now)
  else:self._segment(im,a,ax,b,bx,now)
 def _segment(self,im,a,ax,b,bx,now):
  if b<a:return
  ya=max(8.64,min(self.JUDGE_Y,self.y(a,now)));yb=max(8.64,min(self.JUDGE_Y,self.y(b,now)))
  if abs(ya-yb)<.01 and ax==bx:return
  if a==b:
   l=min(self.x(ax,ya),self.x(bx,ya));r=max(self.x(ax,ya),self.x(bx,ya));h=self.width(ya)*.13
   self.quad(im,'holdbodyhorizontal',[(l,ya-h/2),(r,ya-h/2),(r,ya+h/2),(l,ya+h/2)])
  else:
   wa=self.width(ya)*.14;wb=self.width(yb)*.14
   self.quad(im,'holdbodyvertical',[(self.x(bx,yb)-wb/2,yb),(self.x(bx,yb)+wb/2,yb),(self.x(ax,ya)+wa/2,ya),(self.x(ax,ya)-wa/2,ya)])
 def frame(self,now,duration):
  im=self.base.copy();glow=set()
  distance=self.scroll.travel(now)
  visible=np.flatnonzero((self.end_array>=now-.14)&(self.high>=distance-.2)&(self.low<=distance+self.approach))
  for index in visible:
   n=self.notes[index];at,pos,_,direction,tail,points=n;end=self.ends[index]
   if end<now-.14:continue
   if 0<=now-at<.14:glow.add(int(pos*self.keys/256))
   if points:
    prev=at;px=pos;current=pos
    for pt,nx in points:
     if pt>=now:
      self.segment(im,max(prev,now),px,pt,px,now)
      if nx!=px:
       self.segment(im,pt,px,pt,nx,now);yy=self.y(pt,now);size=self.width(yy)*.37
       for xx in [px,nx]:self.marker(im,'node',xx,yy,size,size)
     if pt<=now:current=nx
     prev=pt;px=nx
    self.head(im,current,self.y(max(at,now),now),True)
    if at<=now<=end:glow.add(int(current*self.keys/256))
   elif at>=now:self.head(im,pos,self.y(at,now),direction!=0)
   ep=points[-1][1] if points else pos;et=end if points else at;di=tail if points else direction
   if end>at and end>=now and not di&10:
    yy=self.y(end,now);w=self.width(yy)*.42;self.marker(im,'holdend',ep,yy,w,w*80/128)
   if di&10 and et>=now and self.TOP_Y<=self.y(et,now)<=self.JUDGE_Y+16:
    yy=self.y(et,now);w=self.width(yy);sign=1 if di&2 else -1;xx=self.x(ep,yy);xe=xx+sign*w*.83;h=w*.13
    self.quad(im,'holdbodyhorizontal',[(min(xx,xe),yy-h/2),(max(xx,xe),yy-h/2),(max(xx,xe),yy+h/2),(min(xx,xe),yy+h/2)])
    target=ep+sign*w*.83/(2*self.half(yy))*256
    self.marker(im,'arrowright' if sign>0 else 'arrowleft',target,yy,w*.67,w*.67*182/148)
  d=ImageDraw.Draw(im)
  for lane in glow:
   cx=self.x((lane+.5)*256/self.keys,self.JUDGE_Y);w=self.half(self.JUDGE_Y)/self.keys*.86
   d.rounded_rectangle((cx-w,self.JUDGE_Y-4,cx+w,self.JUDGE_Y+8),radius=4,fill='#8cffff')
  combo=bisect.bisect_right(self.completed,now)
  if combo:d.text((640,190),str(combo),font=self.large,anchor='mm',fill='#efa8d8',stroke_width=1,stroke_fill='#4b2245')
  if glow:d.text((640,125),'AUTO',font=self.judge,anchor='mm',fill='#b2f8db',stroke_width=1,stroke_fill='#12352e')
  d.text((1255,548),f'{int(now)//60:02}:{int(now)%60:02} / {int(duration)//60:02}:{int(duration)%60:02}',font=self.font,anchor='ra',fill='#dbf5ff')
  return im

def main():
 ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--source',type=Path,required=True);ap.add_argument('--output',type=Path,required=True);ap.add_argument('--limit',type=float);ap.add_argument('--title',default='A Site De La Rue');ap.add_argument('--subtitle',default='LV.13 / SC10');args=ap.parse_args()
 doc=json.loads((args.source/'chart.json').read_bytes());c=doc['chart'];audio=args.source/'music.ogg';ff=imageio_ffmpeg.get_ffmpeg_exe()
 probe=subprocess.run([ff,'-hide_banner','-i',str(audio)],stderr=subprocess.PIPE,text=True).stderr;m=re.search(r'Duration: (\d+):(\d+):(\d+\.\d+)',probe)
 if not m:raise RuntimeError('Audio duration unavailable')
 audio_seconds=int(m[1])*3600+int(m[2])*60+float(m[3]);offset=c['audio_start']
 stage=Stage(c,args.source/'cover.jpg',args.title,args.subtitle);duration=max(max(stage.ends)+2,audio_seconds+offset)
 if not 0<duration<=600 or len(stage.notes)>40000 or c['keys'] not in (4,5,6):raise ValueError('Unsupported chart size or duration')
 if args.limit:duration=min(duration,args.limit)
 if offset<0:raise ValueError('Negative audio offset needs explicit trimming')
 args.output.parent.mkdir(parents=True,exist_ok=True)
 command=[ff,'-loglevel','error','-f','rawvideo','-pix_fmt','rgb24','-s','1280x576','-r','30','-i','-','-i',str(audio),'-af',f'adelay={round(offset*1000)}:all=1,apad','-t',str(duration),'-c:v','libx264','-threads',os.environ.get('MALODY_FFMPEG_THREADS','2'),'-preset','veryfast','-crf','24','-pix_fmt','yuv420p','-c:a','aac','-b:a','128k','-movflags','+faststart','-y',str(args.output)]
 total=math.ceil(duration*30);started=time.monotonic()
 with args.output.with_suffix('.log').open('wb') as log:
  proc=subprocess.Popen(command,stdin=subprocess.PIPE,stderr=log)
  try:
   for f in range(total):
    frame=stage.frame(f/30,duration)
    if f in {min(total-1,30*30),min(total-1,60*30)}:frame.save(args.output.with_name(args.output.stem+f'-{f//30}s.jpg'))
    proc.stdin.write(frame.tobytes())
    if f%900==0:print(f'{f}/{total} frames, elapsed {time.monotonic()-started:.1f}s',flush=True)
  finally:proc.stdin.close()
  if proc.wait():raise RuntimeError('Encoder failed; inspect output log')
 print(json.dumps({'path':str(args.output),'duration':duration,'bytes':args.output.stat().st_size,'notes':len(stage.notes),'render_seconds':round(time.monotonic()-started,1)}),flush=True)
if __name__=='__main__':main()
