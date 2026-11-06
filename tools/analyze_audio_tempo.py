"""MP3 -> local tempo segments and change-time estimates. Experimental, audio only."""
from pathlib import Path
import os,sys
ROOT=Path(__file__).resolve().parents[1]
os.environ.setdefault('NUMBA_CACHE_DIR',str(ROOT/'build/tempo-numba-cache'))
os.environ.setdefault('OPENBLAS_NUM_THREADS','1');os.environ.setdefault('OMP_NUM_THREADS','1')
sys.path.insert(0,str(ROOT/'tools/tempo-libs'))
sys.path.append(str(ROOT/'tools/python-libs'))
import argparse,csv,html,json,math,subprocess,time
import numpy as np
from scipy.ndimage import median_filter
import librosa,imageio_ffmpeg

def decode(path):
 # Bound duration before decoding, and cap PCM output to 15 minutes.
 ff=imageio_ffmpeg.get_ffmpeg_exe()
 r=subprocess.run([ff,'-v','error','-threads','1','-i',str(path),'-t','900','-vn','-ac','1','-ar','22050','-f','f32le','-'],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 if r.returncode:raise ValueError('音频无法解码')
 y=np.frombuffer(r.stdout,dtype='<f4').copy()
 if len(y)<22050*5:raise ValueError('请提供至少5秒的音频')
 return y,22050

def fit_pulse(events,start,end,bpm):
 selected=events[(events>=start)&(events<end)]
 if len(selected)<3:return 0.
 # A beat phase is inferred independently on each side of a candidate boundary.
 phases=np.linspace(0,1,48,endpoint=False)
 distance=np.abs((selected[:,None]*bpm/60-phases[None,:]+.5)%1-.5)
 return float(np.exp(-.5*(distance/.07)**2).sum(axis=0).max())

def analyze(y,sr,minimum=60,maximum=240,hint=None,onset_override=None):
 hop=220;fps=sr/hop
 onset=librosa.onset.onset_strength(y=y,sr=sr,hop_length=hop,n_fft=1024,n_mels=64) if onset_override is None else np.asarray(onset_override,dtype=float)
 onset=np.maximum(0,onset-np.percentile(onset,35));peak=float(np.percentile(onset,98))
 if peak<1e-6:raise ValueError('音频中没有足够清晰的节奏，无法可靠估计')
 onset=np.clip(onset/peak,0,3)
 duration=len(y)/sr;centers=np.arange(0,len(onset),max(1,round(fps*.2)))
 size=round(fps*8);size+=size%2;pad=np.pad(onset,(size//2,size//2))
 windows=np.stack([pad[c:c+size] for c in centers])*np.hanning(size)
 spectrum=np.fft.rfft(windows,n=2*size,axis=1)
 ac=np.fft.irfft(spectrum*np.conj(spectrum),n=2*size,axis=1)[:,:size]
 ac/=np.maximum(ac[:,0:1],1e-8)
 bpms=np.arange(minimum,maximum+.25,.5);lag=60*fps/bpms
 def interp(lags):
  low=np.floor(lags).astype(int);frac=lags-low
  return ac[:,low]*(1-frac)+ac[:,low+1]*frac
 scores=interp(lag)+.5*interp(2*lag)+.25*interp(3*lag)
 scores-=.025*np.log2(bpms/(hint or 140))**2
 if hint:scores-=.15*np.log2(bpms/hint)**2
 energy=windows.sum(axis=1)
 ratio=np.abs(np.log2(bpms[:,None]/bpms[None,:]))
 transition=.12*ratio+.035*(ratio>.06)
 dp=scores[0].copy();back=np.zeros(scores.shape,dtype=np.int16)
 for i in range(1,len(scores)):
  candidate=dp[:,None]-transition
  back[i]=np.argmax(candidate,axis=0);dp=scores[i]+candidate[back[i],np.arange(len(bpms))];dp-=dp.max()
 states=np.zeros(len(scores),dtype=int);states[-1]=int(np.argmax(dp))
 for i in range(len(scores)-1,0,-1):states[i-1]=back[i,states[i]]
 values=median_filter(bpms[states],size=9,mode='nearest');times=centers/fps
 # Find persistent plateau changes; small frame-to-frame jitter is not a new segment.
 cuts=[0];baseline=values[0];last=0
 for i in range(1,len(values)):
  if abs(values[i]-baseline)>max(3,baseline*.04) and i-last>=10:
   future=values[i:min(len(values),i+10)]
   if len(future)>=5 and abs(float(np.median(future))-baseline)>max(3,baseline*.04):cuts.append(i);last=i;baseline=float(np.median(future))
  elif i-last>10:baseline=float(np.median(values[max(last,i-10):i+1]))
 cuts.append(len(values))
 segments=[]
 for a,b in zip(cuts,cuts[1:]):
  bpm=float(np.median(values[a:b]));chosen=np.argmin(abs(bpms-bpm))
  strength=float(np.clip(np.median(scores[a:b,chosen])/1.4,0,1))
  quiet=float(np.median(energy[a:b]))<max(1,float(np.median(energy))*.12)
  if segments and abs(bpm-segments[-1]['bpm'])<max(3,bpm*.04):segments[-1]['end']=float(times[b]) if b<len(times) else duration;continue
  segments.append({'start':float(times[a]),'end':float(times[b]) if b<len(times) else duration,'bpm':bpm,'rhythm_strength':round(strength,3),'uncertain':quiet or strength<.25})
 # Refine candidate change points against transient timing instead of reporting
 # the center of an eight-second analysis window as an exact boundary.
 peaks=librosa.util.peak_pick(onset,pre_max=3,post_max=3,pre_avg=10,post_avg=10,delta=.12,wait=5)
 events=peaks/fps
 for i in range(1,len(segments)):
  a,b=segments[i-1],segments[i];center=b['start'];lo=max(a['start']+1,center-3);hi=min(b['end']-1,center+3)
  candidates=np.arange(lo,hi,.02)
  if len(candidates):
   fits=[fit_pulse(events,max(a['start'],center-6),t,a['bpm'])+fit_pulse(events,t,min(b['end'],center+6),b['bpm']) for t in candidates]
   best=float(candidates[int(np.argmax(fits))]);a['end']=best;b['start']=best
 for s in segments:
  s['start']=round(s['start'],2);s['end']=round(s['end'],2)
  if s['end']-s['start']<4:s['uncertain']=True
  s['alternatives']=[round(v,1) for v in [s['bpm']/2,s['bpm']*2] if 30<=v<=480]
 return {'duration':round(duration,3),'segments':segments,'changes':[{'time':s['start'],'from_bpm':segments[i-1]['bpm'],'to_bpm':s['bpm'],'needs_review':s['uncertain'] or segments[i-1]['uncertain']} for i,s in enumerate(segments) if i],'method':'audio-only experimental; changes are estimates; half/double tempo ambiguous','curve':[{'time':round(float(t),2),'bpm':float(b)} for t,b in zip(times,values)]}

def export(doc,source,out):
 out.mkdir(parents=True,exist_ok=True)
 (out/'tempo.json').write_text(json.dumps(doc,ensure_ascii=False,indent=2),encoding='utf-8')
 with (out/'tempo.csv').open('w',newline='',encoding='utf-8-sig') as f:
  w=csv.writer(f);w.writerow(['开始秒','结束秒','估计BPM','节奏强度(非概率)','需复核','半速/倍速候选','网格一致比例','复核原因'])
  for s in doc['segments']:w.writerow([s['start'],s['end'],s['bpm'],s['rhythm_strength'],s['uncertain'],' / '.join(map(str,s['alternatives'])),s.get('grid_agreement',''),'；'.join(s.get('review_reasons',[]))])
 ymin=min(p['bpm'] for p in doc['curve'])-10;ymax=max(p['bpm'] for p in doc['curve'])+10
 points=' '.join(f"{40+920*p['time']/doc['duration']:.1f},{240-200*(p['bpm']-ymin)/(ymax-ymin):.1f}" for p in doc['curve'])
 rows=''.join(f"<tr><td>{s['start']:.2f}–{s['end']:.2f}s</td><td>{s['bpm']:g}</td><td>{s['rhythm_strength']:.2f}</td><td>{'需复核' if s['uncertain'] else '检测到节奏'}</td><td>{' / '.join(map(str,s['alternatives']))}</td><td>{html.escape('；'.join(s.get('review_reasons',[])) or ('原算法提示弱节奏或短片段' if s['uncertain'] else '未发现明显冲突，仍需校拍'))}</td></tr>" for s in doc['segments'])
 page=f'''<!doctype html><meta charset="utf-8"><title>BPM检测结果</title><style>body{{font:16px system-ui;background:#101b29;color:#e4f2ff;max-width:1100px;margin:40px auto;padding:20px}}svg{{background:#17283c;width:100%;border-radius:16px}}td,th{{padding:14px;text-align:left;border-bottom:1px solid #345}}table{{width:100%}}p{{line-height:1.8;color:#b1c7dc}}</style><h1>{html.escape(source.name)} · BPM检测</h1><p>纯音频估计 · 曲线用于寻找变速段。表格时间不是精确节拍锚点；半速/倍速存在歧义。节奏强度是算法指标，不是正确概率。</p><svg viewBox="0 0 1000 280"><polyline points="{points}" fill="none" stroke="#63dfd2" stroke-width="2"/><text x="40" y="265" fill="white">0s</text><text x="900" y="265" fill="white">{doc['duration']:.1f}s</text><text x="5" y="25" fill="white">{ymax:g}</text><text x="5" y="240" fill="white">{ymin:g}</text></svg><h2>分段 BPM / 疑似变速点</h2><table><tr><th>时间</th><th>BPM</th><th>节奏强度</th><th>状态</th><th>半速/倍速候选</th></tr>{rows}</table>'''
 page=page.replace('<th>半速/倍速候选</th>','<th>半速/倍速候选</th><th>网格复核</th>')
 if 'grid_verification' in doc:
  page=page.replace('<h2>分段', '<p>已启用第二种相位网格与重拍检查。方法之间一致不等于 BPM 正确；分歧、累计漂移及半速／倍速／三连音歧义会标记复核，不自动覆盖候选。</p><h2>分段')
 (out/'report.html').write_text(page,encoding='utf-8')

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('audio',nargs='?',type=Path);parser.add_argument('--out',type=Path,default=ROOT/'dist/audio-tempo');parser.add_argument('--min-bpm',type=float,default=60);parser.add_argument('--max-bpm',type=float,default=240);parser.add_argument('--hint-bpm',type=float);args=parser.parse_args()
 picked=args.audio is None
 if picked:
  import tkinter as tk
  from tkinter import filedialog
  window=tk.Tk();window.withdraw();selected=filedialog.askopenfilename(title='选择要检测BPM的MP3',filetypes=[('音频','*.mp3 *.ogg *.wav *.m4a'),('所有文件','*.*')]);window.destroy()
  if not selected:return
  args.audio=Path(selected)
  args.out=args.out/time.strftime('%Y%m%d-%H%M%S')
 if not 40<=args.min_bpm<args.max_bpm<=360:raise ValueError('BPM范围需在40～360内')
 print('正在读取音频与分析节奏……',flush=True);y,sr=decode(args.audio);doc=analyze(y,sr,args.min_bpm,args.max_bpm,args.hint_bpm);export(doc,args.audio,args.out)
 print(json.dumps({'segments':doc['segments'],'changes':doc['changes'],'report':str(args.out/'report.html')},ensure_ascii=False),flush=True)
 if picked:
  import webbrowser
  webbrowser.open((args.out/'report.html').resolve().as_uri())
if __name__=='__main__':main()
