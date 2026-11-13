"""Audio-only metronome-grid decoder; no chart metadata or song-specific rules."""
import numpy as np
from scipy.ndimage import maximum_filter1d,median_filter
from scipy.signal import find_peaks
import ruptures

def grid_decode(beat,down,onset,duration,fps=50,minimum=60,maximum=260):
 feature=maximum_filter1d(np.asarray(beat),size=5)*.8+maximum_filter1d(np.asarray(onset),size=3)*.2
 feature=np.clip(feature,0,1)
 if len(feature)<fps*5 or float(np.max(feature))<.05:raise ValueError('No reliable beat evidence in audio')
 down_curve=maximum_filter1d(np.asarray(down),size=3)
 peak_idx,peak_properties=find_peaks(np.asarray(beat),height=.15,distance=max(2,int(fps*.1)))
 peak_times=peak_idx/fps;peak_strength=peak_properties['peak_heights']
 tempos=np.arange(minimum,maximum+.1,.5);periods=60/tempos
 centers=np.arange(0,duration,.5);phases=np.arange(24)/24
 numbers=np.arange(-int(np.ceil(maximum*4/60))-2,int(np.ceil(maximum*4/60))+3)
 offsets=(numbers[None,None,:]+phases[None,:,None])*periods[:,None,None]
 weights=np.maximum(0,1-abs(offsets)/4)
 scores=[];best_phases=[]
 for t in centers:
  at=t+offsets;valid=(at>=0)&(at<duration);w=weights*valid
  observed=np.interp((at*fps).ravel(),np.arange(len(feature)),feature).reshape(at.shape)
  fit=(observed*w).sum(axis=2)/np.maximum(w.sum(axis=2),1)
  down_observed=np.interp((at*fps).ravel(),np.arange(len(down_curve)),down_curve).reshape(at.shape)
  measure_fit=np.zeros(fit.shape)
  for meter in (3,4):
   for first in range(meter):
    mw=w*((numbers%meter)==first)[None,None,:]
    measure_fit=np.maximum(measure_fit,(down_observed*mw).sum(axis=2)/np.maximum(mw.sum(axis=2),1))
  fit+=.2*measure_fit
  # Checking grid ticks alone would reward a slow grid that simply skips most
  # real beats. Also measure how much detected quarter-note evidence it explains.
  selected=(peak_times>=t-4)&(peak_times<t+4)
  events=peak_times[selected];importance=peak_strength[selected]*np.maximum(0,1-abs(events-t)/4)
  if importance.sum()>0:
   phase_error=(events[None,None,:]-t)/periods[:,None,None]-phases[None,:,None]
   distance=abs((phase_error+.5)%1-.5)*periods[:,None,None]
   explained=(np.exp(-.5*(distance/.04)**2)*importance[None,None,:]).sum(axis=2)/importance.sum()
   fit+=.6*explained
  best_phases.append(fit.argmax(axis=1));scores.append(fit.max(axis=1))
 scores=np.asarray(scores);scores-=.02*np.log2(tempos/120)**2
 # A new tempo needs accumulated evidence, not just a single loud drum fill.
 diff=abs(np.log2(tempos[:,None]/tempos[None,:]));cost=.35*(diff>.025)+.7*diff
 back=np.zeros(scores.shape,dtype=np.int16);dp=scores[0].copy()
 for i in range(1,len(scores)):
  candidates=dp[:,None]-cost;back[i]=candidates.argmax(axis=0);dp=scores[i]+candidates[back[i],np.arange(len(tempos))];dp-=dp.max()
 path=np.empty(len(scores),dtype=int);path[-1]=dp.argmax()
 for i in range(len(path)-1,0,-1):path[i-1]=back[i,path[i]]
 curve=median_filter(tempos[path],size=5,mode='nearest')
 cuts=ruptures.Pelt(model='l2',min_size=6,jump=1).fit(np.log(curve)[:,None]).predict(pen=.003)
 pieces=[];a=0
 for b in cuts:
  bpm=float(np.median(curve[a:b]));start=centers[a];end=centers[b] if b<len(centers) else duration
  if pieces and abs(bpm/pieces[-1]['bpm']-1)<.02:pieces[-1]['end']=float(end)
  else:pieces.append({'start':float(start),'end':float(end),'bpm':bpm})
  a=b
 # Fit one phase and tempo over an entire candidate segment. Far-end alignment
 # supplies evidence that a plausible local rhythm is also a consistent grid.
 for p in pieces:
  start,end,bpm=p['start'],p['end'],p['bpm'];margin=min(1,(end-start)/5)
  grid_tempos=np.arange(max(minimum,bpm*.97),min(maximum,bpm*1.03)+.025,.05)
  best=None
  for candidate in grid_tempos:
   period=60/candidate;beats=np.arange(0,end-start+period,period)
   phase=phases*period;at=start+phase[:,None]+beats[None,:]
   valid=(at>=start+margin)&(at<end-margin)
   hits=np.interp((at*fps).ravel(),np.arange(len(feature)),feature).reshape(at.shape)
   fits=(hits*valid).sum(axis=1)/np.maximum(valid.sum(axis=1),1)
   j=int(fits.argmax())
   if best is None or fits[j]>best[0]:best=(float(fits[j]),float(candidate),float(start+phase[j]))
  strength,bpm,offset=best
  period=60/bpm;grid=np.arange(offset,end,period);grid=grid[(grid>start+.5)&(grid<end-.5)]
  peaks=find_peaks(np.asarray(beat),height=.15,distance=max(2,int(fps*.1)))[0]/fps
  observed=peaks[(peaks>=start)&(peaks<end)]
  residual=[]
  for target in grid:
   if len(observed):residual.append(float(observed[np.argmin(abs(observed-target))]-target))
  residual=np.asarray(residual);aligned=abs(residual)<=.045
  drift=abs(float(np.median(residual[-max(2,len(residual)//3):])-np.median(residual[:max(2,len(residual)//3)]))) if len(residual)>=6 else float('inf')
  match=float(np.mean(aligned)) if len(aligned) else 0
  p.update(bpm=round(bpm,3),offset=round(offset,4),rhythm_strength=round(strength,3),uncertain=strength<.35 or end-start<5 or match<.7 or drift>.025,alternatives=[round(bpm/2,2),round(bpm*2,2)],grid_hit_fraction=round(match,3),grid_drift_ms=round(drift*1000,1) if np.isfinite(drift) else None)
 # Refine each boundary using fixed grids fitted on both sides. If neither
 # side explains the transition, retain a review marker rather than certainty.
 for i in range(1,len(pieces)):
  left,right=pieces[i-1],pieces[i];center=right['start'];lo=max(left['start']+2,center-3);hi=min(right['end']-2,center+3)
  candidates=np.arange(lo,hi,.02);window_start=max(left['start'],center-5);window_end=min(right['end'],center+5)
  def grid_hits(p):
   period=60/p['bpm'];first=p['offset']+np.ceil((window_start-p['offset'])/period)*period
   at=np.arange(first,window_end,period);hit=np.interp(at*fps,np.arange(len(feature)),feature)
   return at,hit
  lt,lh=grid_hits(left);rt,rh=grid_hits(right)
  if len(candidates):
   fits=[float((lh[lt<t]-.25).sum()+(rh[rt>=t]-.25).sum()) for t in candidates]
   boundary=float(candidates[int(np.argmax(fits))]);left['end']=boundary;right['start']=boundary
 for p in pieces:
  p['start']=round(p['start'],3);p['end']=round(p['end'],3)
 return {'duration':duration,'segments':pieces,'changes':[{'time':p['start'],'from_bpm':pieces[i-1]['bpm'],'to_bpm':p['bpm'],'needs_review':p['uncertain'] or pieces[i-1]['uncertain']} for i,p in enumerate(pieces) if i],'curve':[{'time':float(t),'bpm':float(b)} for t,b in zip(centers,curve)],'method':'neural beat probabilities + whole-segment phase-grid alignment; audio only'}

def verify_segments(primary,grid):
 """Flag disagreements without silently replacing the musical beat level."""
 import copy
 result=copy.deepcopy(primary)
 for segment in result['segments']:
  overlap=match=0.;reasons=[];drifts=[]
  for check in grid['segments']:
   width=max(0,min(segment['end'],check['end'])-max(segment['start'],check['start']))
   if not width:continue
   overlap+=width
   ratio=segment['bpm']/check['bpm']
   if abs(ratio-1)<=.03:match+=width
   elif min(abs(ratio-x)/x for x in [.5,2,2/3,1.5])<=.03:reasons.append('拍级／三连音分组存在歧义')
   else:reasons.append('两种检测方法不一致')
   if check.get('grid_drift_ms') is not None:drifts.append(check['grid_drift_ms'])
  agreement=match/max(overlap,1e-8)
  if agreement<.7:reasons.append('缺少整段网格一致性支持')
  if drifts and max(drifts)>25:reasons.append('局部网格存在累计漂移')
  segment['grid_agreement']=round(agreement,3)
  segment['review_reasons']=list(dict.fromkeys(reasons))
  if reasons:segment['uncertain']=True
 for i,change in enumerate(result['changes']):change['needs_review']=result['segments'][i]['uncertain'] or result['segments'][i+1]['uncertain']
 result['grid_verification']=grid
 result['method']='neural tempo candidates with independent phase-grid, downbeat and missed-beat audit; audio only'
 return result
