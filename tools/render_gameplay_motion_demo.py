"""Offline synthetic trajectory preview, not an Android emulator or device recording.
Reads coordinates emitted by the actual Java GameplayMotion implementation.
"""
from pathlib import Path
import argparse,collections,csv,subprocess,sys
import numpy as np
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/python-libs'))
import imageio_ffmpeg
from PIL import Image,ImageDraw,ImageFont

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--note-profile',choices=('original','compact'),default='original')
 parser.add_argument('--motion-profile',choices=('linear','reference'),default='reference')
 parser.add_argument('--output',type=Path,default=ROOT/'dist/autoplay-1.6.29')
 args=parser.parse_args()
 build=ROOT/'build/autoplay-tests';build.mkdir(parents=True,exist_ok=True)
 subprocess.run(['javac','-encoding','UTF-8','-d',str(build),str(ROOT/'android/src/cn/gmiya/malodylink/GameplayMotion.java'),str(ROOT/'tests/GameplayMotionTest.java')],check=True)
 mode='--reference-frames' if args.motion_profile=='reference' else '--frames'
 with (build/'motion-frames.csv').open('wb') as f:subprocess.run(['java','-cp',str(build),'GameplayMotionTest',mode],stdout=f,check=True)
 frames=collections.defaultdict(list)
 with (ROOT/'build/autoplay-tests/motion-frames.csv').open(encoding='utf-8-sig') as f:
  for v in csv.DictReader(f):frames[int(v['frame'])].append(v)
 out=args.output.resolve();out.mkdir(parents=True,exist_ok=True)
 font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19)
 assets={n:Image.open(ROOT/f'android/assets/gameplay-skin/rm-notes/{n}.png').convert('RGBA') for n in ('note','holdhead','holdbodyvertical','holdbodyhorizontal','holdend','node','arrowleft','arrowright')}
 sources=[assets['note']]*6
 def half(y):return 400*(.20666666666666667+(1-.20666666666666667)*(y/576-.015)/.72)
 def x(lane,y):return 640+(lane/6*2-1)*half(y)
 def quad(im,sprite,pts):
  left=max(0,int(min(p[0] for p in pts))-2);top=max(0,int(min(p[1] for p in pts))-2)
  right=min(im.width,int(max(p[0] for p in pts))+3);bottom=min(im.height,int(max(p[1] for p in pts))+3)
  if right<=left or bottom<=top:return
  A=[];B=[]
  for (dx,dy),(sx,sy) in zip(pts,[(0,0),(sprite.width,0),(sprite.width,sprite.height),(0,sprite.height)]):
   dx-=left;dy-=top
   A.extend([[dx,dy,1,0,0,0,-sx*dx,-sx*dy],[0,0,0,dx,dy,1,-sy*dx,-sy*dy]]);B.extend([sx,sy])
  try:coeff=np.linalg.solve(np.array(A),np.array(B))
  except np.linalg.LinAlgError:return
  patch=sprite.transform((right-left,bottom-top),Image.Transform.PERSPECTIVE,coeff,Image.Resampling.BICUBIC)
  im.paste(patch,(left,top),patch)
 def nw(y):return 2*half(y)*290/1680
 def render_head(im,sprite,lane,y,h):
  y0=y-h;y1=y+h;w0=nw(y0);w1=nw(y1)
  quad(im,sprite,[(x(lane,y0)-w0/2,y0),(x(lane,y0)+w0/2,y0),(x(lane,y1)+w1/2,y1),(x(lane,y1)-w1/2,y1)])
 def marker(im,key,cx,cy,w,h):
  sprite=assets[key].resize((max(2,int(w)),max(2,int(h))),Image.Resampling.LANCZOS);im.paste(sprite,(int(cx-w/2),int(cy-h/2)),sprite)
 def render_arrow(im,lane,y,sign):
  width=nw(y);cx=x(lane,y);end=cx+sign*width*.83;h=width*.13
  quad(im,assets['holdbodyhorizontal'],[(min(cx,end),y-h/2),(max(cx,end),y-h/2),(max(cx,end),y+h/2),(min(cx,end),y+h/2)])
  marker(im,'arrowright' if sign>0 else 'arrowleft',end,y,width*.67,width*.67*182/148)
 base=Image.new('RGB',(1280,576),'#171b23');d=ImageDraw.Draw(base)
 track=Image.open(ROOT/'android/assets/gameplay-skin/1712474142416.png').convert('RGBA')
 sy=576*.735/824;tw=int(1280*.935);th=int(1200*sy);track=track.resize((tw,th));base.paste(track,((1280-tw)//2,int(-50*sy)),track)
 for lane in range(7):d.line((x(lane,8.64),8.64,x(lane,423.36),423.36),fill='#43464f',width=1)
 for lane in range(6):
  d.polygon([(x(lane,428)+3,428),(x(lane+1,428)-3,428),(x(lane+1,487)-3,487),(x(lane,487)+3,487)],fill='#252c34',outline='#a48d72')
  d.line((x(lane,485)+4,485,x(lane+1,485)-4,485),fill='#387289',width=2)
 d.line((x(0,423.36),423.36,x(6,423.36),423.36),fill='#d8b590',width=3)
 d.text((24,18),'RM VI · 离线轨迹演示（非真机录屏）',font=font,fill='#c1eff1')
 subtitle='薄键候选 · 恒速 / 同谱面 / 同流速' if args.note_profile=='compact' else '合成谱面 · 固定屏幕速度 · Java 实际几何坐标'
 if args.motion_profile=='reference':subtitle='新素材 · 单键 / 长按 / 折线 / 滑键 · 已确认运动曲线'
 d.text((24,46),subtitle,font=font,fill='#a4bcc9')
 # Mixed static fixture shows all supplied parts without pretending to be a real chart.
 mixed=base.copy()
 for lane,y in [(1.5,350),(4.5,190),(3.5,90)]:render_head(mixed,assets['note'],lane,y,nw(y)*116/290/2)
 lane=.5;start=385;turn=250;end=110
 for a,b,ln in [(start,turn,lane),(turn,end,1.5)]:
  wa=nw(a)*.14;wb=nw(b)*.14
  quad(mixed,assets['holdbodyvertical'],[(x(ln,b)-wb/2,b),(x(ln,b)+wb/2,b),(x(ln,a)+wa/2,a),(x(ln,a)-wa/2,a)])
 h=nw(turn)*.13;xx=x(.5,turn);xe=x(1.5,turn)
 quad(mixed,assets['holdbodyhorizontal'],[(xx,turn-h/2),(xe,turn-h/2),(xe,turn+h/2),(xx,turn+h/2)])
 for ln in [.5,1.5]:marker(mixed,'node',x(ln,turn),turn,nw(turn)*.37,nw(turn)*.37)
 marker(mixed,'holdend',x(1.5,end),end,nw(end)*.42,nw(end)*.42*80/128)
 render_head(mixed,assets['holdhead'],.5,start,nw(start)*116/290/2)
 render_head(mixed,assets['holdhead'],4.5,390,nw(390)*116/290/2);render_arrow(mixed,4.5,390,1)
 mixed.save(out/'asset-preview.png')
 command=[imageio_ffmpeg.get_ffmpeg_exe(),'-loglevel','error','-f','rawvideo','-pix_fmt','rgb24','-s','1280x576','-r','30','-i','-','-an','-c:v','libx264','-threads','2','-preset','ultrafast','-crf','22','-pix_fmt','yuv420p','-y',str(out/'constant-speed-demo.mp4')]
 with (ROOT/'build/autoplay-tests/ffmpeg.log').open('wb') as log:
  proc=subprocess.Popen(command,stdin=subprocess.PIPE,stderr=log)
  try:
   for frame in range(180):
    im=base.copy();d=ImageDraw.Draw(im)
    for note in frames[frame]:
     lane=int(note['lane']);y=float(note['y']);w=int(float(note['width']));h=int(float(note['halfheight'])*2);cx=x(lane+.5,y)
     if args.note_profile=='compact':
      # Candidate styling only: leave the app's motion and note-width geometry unchanged.
      depth=max(0,min(1,(y/576-.015)/.72))
      h=round(576*(.024+.014*depth))
     sprite=assets['holdhead'] if lane==4 else sources[lane]
     render_head(im,sprite,lane+.5,y,h/2)
     if lane==4:render_arrow(im,lane+.5,y,1)
    timing='可见行程 0.588s' if args.motion_profile=='reference' else 'Flow 2.0x'
    d.text((30,548),f'{frame/30:.2f}s    {timing}',font=font,fill='#c1eff1')
    if frame==90:im.save(out/'motion-preview.png')
    proc.stdin.write(im.tobytes())
  finally:proc.stdin.close()
  if proc.wait()!=0:raise RuntimeError('Video encoding failed; see build log')
 print(out/'constant-speed-demo.mp4')
if __name__=='__main__':main()
