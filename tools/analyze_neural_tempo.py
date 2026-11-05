"""Audio-only Beat This! + segmented beat-grid regression experiment."""
from pathlib import Path
import os,sys
ROOT=Path(__file__).resolve().parents[1]
os.environ['TORCH_HOME']=str(ROOT/'tools/models/torch')
os.environ['NUMBA_CACHE_DIR']=str(ROOT/'build/tempo-numba-cache')
os.environ['OPENBLAS_NUM_THREADS']='1';os.environ['OMP_NUM_THREADS']='2'
sys.path[:0]=[str(ROOT/'tools/beat-libs'),str(ROOT/'tools/beat-extra'),str(ROOT/'tools/tempo-libs'),str(ROOT/'tools/python-libs')]
import argparse,json,time,urllib.request
import numpy as np
import torch
torch.set_num_threads(2);torch.set_num_interop_threads(1)
from beat_this.inference import Audio2Beats,Audio2Frames
import ruptures
from analyze_audio_tempo import decode,export,analyze

def segment_beats(beats,duration):
 if len(beats)<16:raise ValueError('Too few detected beats')
 idx=np.arange(len(beats),dtype=float)
 cuts=ruptures.Pelt(model='linear',min_size=8,jump=1).fit(np.column_stack([beats,np.ones(len(beats)),idx])).predict(pen=.02)
 pieces=[];start=0
 for end in cuts:
  slope,intercept=np.polyfit(idx[start:end],beats[start:end],1)
  residual=float(np.sqrt(np.mean((beats[start:end]-(slope*idx[start:end]+intercept))**2)))
  pieces.append({'a':start,'b':end,'slope':float(slope),'intercept':float(intercept),'residual':residual})
  start=end
 # Redundant breaks caused by a missed beat must not become a BPM change.
 merged=[]
 for p in pieces:
  if merged and abs(p['slope']/merged[-1]['slope']-1)<.02:
   old=merged[-1];old['b']=p['b'];a,b=old['a'],old['b'];m,c=np.polyfit(idx[a:b],beats[a:b],1);old.update(slope=float(m),intercept=float(c),residual=float(np.sqrt(np.mean((beats[a:b]-(m*idx[a:b]+c))**2))))
  else:merged.append(p)
 boundaries=[0.]
 for left,right in zip(merged,merged[1:]):
  mid=right['a']-.5;denom=left['slope']-right['slope']
  cross=(right['intercept']-left['intercept'])/denom if abs(denom)>1e-8 else mid
  chosen=cross if abs(cross-mid)<3 else mid
  boundaries.append(float(np.clip(left['slope']*chosen+left['intercept'],boundaries[-1],duration)))
 boundaries.append(duration)
 segments=[]
 for i,p in enumerate(merged):
  bpm=60/p['slope'];segments.append({'start':round(boundaries[i],3),'end':round(boundaries[i+1],3),'bpm':round(bpm,3),'rhythm_strength':round(max(0,1-p['residual']/.1),3),'uncertain':p['residual']>.04 or boundaries[i+1]-boundaries[i]<4,'alternatives':[round(bpm/2,2),round(bpm*2,2)],'beat_fit_rmse_seconds':round(p['residual'],4)})
 curve=[{'time':s['start'],'bpm':s['bpm']} for s in segments]
 curve=[p for s in segments for p in [{'time':s['start'],'bpm':s['bpm']},{'time':s['end'],'bpm':s['bpm']}]]
 return {'duration':duration,'segments':segments,'changes':[{'time':s['start'],'from_bpm':segments[i-1]['bpm'],'to_bpm':s['bpm'],'needs_review':s['uncertain'] or segments[i-1]['uncertain']} for i,s in enumerate(segments) if i],'curve':curve,'method':'audio-only Beat This! small0 + PELT beat-grid regression; no chart used','beats':beats.tolist()}

def main():
 ap=argparse.ArgumentParser();ap.add_argument('audio',type=Path);ap.add_argument('--out',type=Path,default=ROOT/'dist/neural-tempo-amara');ap.add_argument('--model',choices=['small0','final0'],default='small0');ap.add_argument('--decoder',choices=['beat-grid','probability'],default='beat-grid');args=ap.parse_args()
 model=ROOT/'tools/models'/f'beat-this-{args.model}.ckpt';model.parent.mkdir(parents=True,exist_ok=True)
 if not model.exists():
  print('Downloading official model weights to E:',flush=True)
  with urllib.request.urlopen(f'https://cloud.cp.jku.at/public.php/dav/files/7ik4RrBKTS273gp/{args.model}.ckpt',timeout=45) as r:model.write_bytes(r.read())
 start=time.monotonic();y,sr=decode(args.audio);print('Audio decoded; starting CPU inference with 2 threads',flush=True)
 tracker=Audio2Beats(checkpoint_path=str(model),device='cpu',dbn=False)
 if args.decoder=='probability':
  beat_logits,_=Audio2Frames.__call__(tracker,y,sr)
  prob=beat_logits.sigmoid().cpu().numpy()
  onset=np.interp(np.arange(int(len(y)/220)+1)*220/sr,np.arange(len(prob))/50,prob)
  d=analyze(y,sr,onset_override=onset);d['method']='Beat This '+args.model+' neural beat probabilities + constrained tempo trajectory, audio only';d['model']=args.model
  export(d,args.audio,args.out);print('Probability trajectory exported',flush=True);return
 beats,downbeats=tracker(y,sr);print('Detected',len(beats),'beats,',len(downbeats),'downbeats; elapsed',round(time.monotonic()-start,1),flush=True)
 d=segment_beats(np.asarray(beats),len(y)/sr);d['downbeats']=np.asarray(downbeats).tolist();d['model']=args.model
 export(d,args.audio,args.out);print(json.dumps({'segments':d['segments'],'elapsed_seconds':round(time.monotonic()-start,1)},ensure_ascii=False),flush=True)
if __name__=='__main__':main()
