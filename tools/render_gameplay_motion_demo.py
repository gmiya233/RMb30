"""Offline synthetic trajectory preview, not an Android emulator or device recording.
Reads coordinates emitted by the actual Java GameplayMotion implementation.
"""
from pathlib import Path
import argparse,collections,csv,subprocess,sys
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/python-libs'))
import imageio_ffmpeg
from PIL import Image,ImageDraw,ImageFont

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--note-profile',choices=('original','compact'),default='original')
 parser.add_argument('--motion-profile',choices=('linear','reference'),default='reference')
 parser.add_argument('--output',type=Path,default=ROOT/'dist/autoplay-1.6.28')
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
 sources=[Image.open(ROOT/f'android/assets/gameplay-skin/6note{i}.png').convert('RGBA') for i in range(1,7)]
 def half(y):return 400*(.20666666666666667+(1-.20666666666666667)*(y/576-.015)/.72)
 def x(lane,y):return 640+(lane/6*2-1)*half(y)
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
 if args.motion_profile=='reference':subtitle='参考视频运动候选 · 近端加速 / 间距展开 / 原厚度'
 d.text((24,46),subtitle,font=font,fill='#a4bcc9')
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
     sprite=sources[lane].resize((max(2,w),max(2,h)));im.paste(sprite,(int(cx-w/2),int(y-h/2)),sprite)
    timing='可见行程 0.588s' if args.motion_profile=='reference' else 'Flow 2.0x'
    d.text((30,548),f'{frame/30:.2f}s    {timing}',font=font,fill='#c1eff1')
    if frame==90:im.save(out/'motion-preview.png')
    proc.stdin.write(im.tobytes())
  finally:proc.stdin.close()
  if proc.wait()!=0:raise RuntimeError('Video encoding failed; see build log')
 print(out/'constant-speed-demo.mp4')
if __name__=='__main__':main()
