"""Offline synthetic trajectory preview, not an Android emulator or device recording.
Reads coordinates emitted by the actual Java GameplayMotion implementation.
"""
from pathlib import Path
import collections,csv,subprocess,sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/python-libs'))
import imageio_ffmpeg
from PIL import Image,ImageDraw,ImageFont

def main():
 build=ROOT/'build/autoplay-tests';build.mkdir(parents=True,exist_ok=True)
 subprocess.run(['javac','-encoding','UTF-8','-d',str(build),str(ROOT/'android/src/cn/gmiya/malodylink/GameplayMotion.java'),str(ROOT/'tests/GameplayMotionTest.java')],check=True)
 with (build/'motion-frames.csv').open('wb') as f:subprocess.run(['java','-cp',str(build),'GameplayMotionTest','--frames'],stdout=f,check=True)
 frames=collections.defaultdict(list)
 with (ROOT/'build/autoplay-tests/motion-frames.csv').open(encoding='utf-8-sig') as f:
  for v in csv.DictReader(f):frames[int(v['frame'])].append(v)
 out=ROOT/'dist/autoplay-1.6.26';out.mkdir(parents=True,exist_ok=True)
 font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19)
 source=Image.open(ROOT/'android/assets/gameplay-skin/6note1.png').convert('RGBA');source=source.crop(source.getbbox())
 def half(y):return 480*(25.9/210+(1-25.9/210)*(y/720-.025)/.735)
 def x(lane,y):return 640+(lane/6*2-1)*half(y)
 base=Image.new('RGB',(1280,720),'#071119');d=ImageDraw.Draw(base)
 for lane in range(6):
  d.polygon([(x(lane,18),18),(x(lane+1,18),18),(x(lane+1,547.2),547.2),(x(lane,547.2),547.2)],fill='#181b21' if lane%2==0 else '#10151b',outline='#40515c')
  d.polygon([(x(lane,555)+3,555),(x(lane+1,555)-3,555),(x(lane+1,626)-3,626),(x(lane,626)+3,626)],fill='#282e34',outline='#a48d72')
 d.line((x(0,547),547,x(6,547),547),fill='#d8b590',width=3)
 d.text((24,18),'RM VI · 离线轨迹演示（非真机录屏）',font=font,fill='#c1eff1')
 d.text((24,46),'合成谱面 · 固定屏幕速度 · Java 实际几何坐标',font=font,fill='#a4bcc9')
 command=[imageio_ffmpeg.get_ffmpeg_exe(),'-loglevel','error','-f','rawvideo','-pix_fmt','rgb24','-s','1280x720','-r','30','-i','-','-an','-c:v','libx264','-threads','2','-preset','ultrafast','-crf','22','-pix_fmt','yuv420p','-y',str(out/'constant-speed-demo.mp4')]
 with (ROOT/'build/autoplay-tests/ffmpeg.log').open('wb') as log:
  proc=subprocess.Popen(command,stdin=subprocess.PIPE,stderr=log)
  try:
   for frame in range(180):
    im=base.copy();d=ImageDraw.Draw(im)
    for note in frames[frame]:
     lane=int(note['lane']);y=float(note['y']);w=int(float(note['width']));h=int(float(note['halfheight'])*2);cx=x(lane+.5,y)
     sprite=source.resize((max(2,w),max(2,h)));im.paste(sprite,(int(cx-w/2),int(y-h/2)),sprite)
    d.text((30,660),f'{frame/30:.2f}s    Flow 2.0x',font=font,fill='#c1eff1')
    if frame==90:im.save(out/'motion-preview.png')
    proc.stdin.write(im.tobytes())
  finally:proc.stdin.close()
  if proc.wait()!=0:raise RuntimeError('Video encoding failed; see build log')
 print(out/'constant-speed-demo.mp4')
if __name__=='__main__':main()
