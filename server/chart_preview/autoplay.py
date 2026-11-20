"""Read-only gameplay timelines and deduplicated audio for automatic previews."""
import hashlib,json,os,shutil
from pathlib import Path,PurePosixPath
from render import parse,Timeline,MAX_BYTES
from imd import parse_imd
REVISION='autoplay-3-signed-scroll'
def atomic(path,data):
 path.parent.mkdir(parents=True,exist_ok=True)
 stage=path.with_suffix(path.suffix+'.tmp')
 with stage.open('wb') as f:f.write(data);f.flush();os.fsync(f.fileno())
 stage.chmod(0o644);stage.replace(path)
def confined(root,directory,name):
 path=PurePosixPath(str(directory).replace('\\','/'))/name
 if path.is_absolute() or '..' in path.parts or ':' in str(path) or '\\' in name or not name:raise ValueError('Invalid asset path')
 result=root.joinpath(*path.parts)
 if result.is_symlink() or not result.resolve().is_relative_to(root.resolve()):raise ValueError('Asset outside source')
 return result

def publish_one(catalogue,row,root,output):
 out=output/'playback';out.mkdir(parents=True,exist_ok=True)
 target=out/(str(row['cid'])+'.json')
 try:
  mc=confined(root,row['path'],row['mc_name'])
  original=None
  for name in [Path(row['mc_name']).stem+'.imd',str(row.get('title',''))+'.imd']:
   candidate=confined(root,row['path'],name)
   if candidate.is_file():original=candidate;break
  source=original or mc
  st=source.stat();mt=mc.stat()
  if st.st_size>MAX_BYTES or mt.st_size>MAX_BYTES:raise ValueError('Chart too large')
  d=json.loads(mc.read_bytes());audio_rows=[n for n in d.get('note',[]) if 'sound' in n and 'x' not in n]
  if len(audio_rows)>1 or any(t.get('delay',0) for t in d.get('time',[])[1:]):raise ValueError('Multiple audio tracks or stops not supported')
  name=audio_rows[0]['sound'] if audio_rows else d.get('meta',{}).get('song',{}).get('file','')
  audio=confined(root,row['path'],name) if name else None
  ast=audio.stat() if audio and audio.is_file() else None
  signature=hashlib.sha256(json.dumps([REVISION,catalogue['server'],row,st.st_size,st.st_mtime_ns,mt.st_size,mt.st_mtime_ns,None if ast is None else [ast.st_size,ast.st_mtime_ns]],sort_keys=True).encode()).hexdigest()
  if target.is_file():
   old=json.loads(target.read_bytes())
   if old.get('revision')==signature and (not old.get('audio') or (out/old['audio']['file']).is_file()):return 'unchanged'
  chart=parse_imd(source.read_bytes(),row.get('keys')) if original else parse(source.read_bytes(),row.get('keys'))
  clock=Timeline(chart.bpms)
  start=0
  if not original and audio_rows:start=clock.seconds(__import__('render').beat(audio_rows[0]['beat']))+float(audio_rows[0].get('offset',0))/1000
  normalized={'keys':chart.keys,'format':chart.source_format,'effects':chart.has_effects,'audio_start':start,'notes':[[clock.seconds(n.start),n.x,n.width,n.direction,n.tail_direction,[[clock.seconds(t),x] for t,x in n.points]] for n in chart.notes]}
  scrolls=[];hs=scroll=1;events={}
  if not original:
   from render import beat
   for e in d.get('effect',[]):
    if 'hs' in e or 'scroll' in e:events.setdefault(clock.seconds(beat(e['beat'])),{}).update({k:e[k] for k in ['hs','scroll'] if k in e})
   for t,e in sorted(events.items()):
    hs=e.get('hs',hs);scroll=e.get('scroll',scroll);v=hs*scroll
    if not isinstance(v,(int,float)) or not __import__('math').isfinite(v) or abs(v)>10000:raise ValueError('Invalid or extreme scroll')
    scrolls.append([t,v])
  normalized['scroll']=scrolls
  normalized['effects']=not original and any(not any(k in e for k in ['hs','scroll']) for e in d.get('effect',[]))
  doc={'schema':1,'server':catalogue['server'],'cid':row['cid'],'server_hash':row['server_hash'],'revision':signature,'chart':normalized}
  if ast and 0<ast.st_size<=33554432 and audio.suffix.lower() in ('.ogg','.mp3','.wav','.m4a'):
   digest=hashlib.sha256(audio.read_bytes()).hexdigest();filename=digest+audio.suffix.lower();dest=out/filename
   if not dest.exists():
    temp=dest.with_suffix(dest.suffix+'.tmp')
    try:os.link(audio,temp)
    except OSError:shutil.copyfile(audio,temp);temp.chmod(0o644)
    temp.replace(dest)
   doc['audio']={'file':filename,'sha256':digest,'bytes':ast.st_size}
  atomic(target,json.dumps(doc,ensure_ascii=False,separators=(',',':'),allow_nan=False).encode());return 'published'
 except (OSError,ValueError,KeyError,TypeError,OverflowError):
  atomic(target,json.dumps({'schema':1,'server':catalogue['server'],'cid':row['cid'],'server_hash':row['server_hash'],'error':'此谱面暂不支持自动游玩预览'},ensure_ascii=False).encode());return 'unavailable'
