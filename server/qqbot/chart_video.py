"""On-demand video jobs; one renderer, bounded queue, versioned persistent cache."""
import asyncio,hashlib,json,os,shutil,signal,sys,tempfile
from dataclasses import dataclass
from pathlib import Path
from b30_commands import command_parts
from b30_data import artwork_candidates
from ranking_queries import text

@dataclass
class Plan:
 key:str
 row:dict
 timeline:Path
 audio:Path
 cover:Path
 output:Path

class ChartVideos:
 COMMANDS={'谱面视频','视频预览'}
 def __init__(self,queries,playback,roots,state,renderer):
  self.queries=queries;self.playback=Path(playback);self.roots=list(map(Path,roots));self.state=Path(state);self.renderer=Path(renderer)
  self.state.mkdir(parents=True,exist_ok=True);self.tasks={};self.slot=asyncio.Semaphore(1)
  self.revision=(self.renderer.parent/'revision.txt').read_text().strip()
 def resolve(self,content):
  _,arg=command_parts(content)
  if not arg or len(content)>200:return '用法：谱面视频 歌名或谱面ID。'
  namespace,rows=self.queries.catalogue()
  found=([rows[int(arg)]] if int(arg) in rows and rows[int(arg)].get('search_visible',True) else []) if arg.isdecimal() else self.queries.search(arg)
  if not found:return '没有找到可生成视频的谱面，请尝试更短的歌名。'
  if len(found)!=1:return '请选择具体谱面ID：\n'+'\n'.join(f"{r['cid']} · {text(r.get('title'),38)} · {text(r.get('version'),35)}" for r in found[:8])
  row=found[0];cid=row['cid'];timeline=self.playback/f'{cid}.json'
  try:
   if timeline.is_symlink() or timeline.stat().st_size>8*1024*1024:raise ValueError()
   raw=timeline.read_bytes();doc=json.loads(raw)
   if doc.get('schema')!=1 or doc.get('server')!=namespace or doc.get('cid')!=cid or doc.get('server_hash')!=row.get('server_hash') or 'error' in doc:raise ValueError()
   name=doc['audio']['file']
   import re
   if not re.fullmatch(r'[a-f0-9]{64}\.(ogg|mp3|wav|m4a)',name):raise ValueError()
   audio=self.playback/name
   if audio.is_symlink() or not 0<audio.stat().st_size<=32*1024*1024:raise ValueError()
   if hashlib.sha256(audio.read_bytes()).hexdigest()!=doc['audio']['sha256']:raise ValueError()
   cover=None
   for digest in list(row.get('artworks',[]))+artwork_candidates(row):
    if not isinstance(digest,str) or not re.fullmatch(r'[a-f0-9]{64}',digest):continue
    for root in self.roots:
     candidate=root/(digest+'.jpg')
     if not candidate.is_symlink() and candidate.is_file() and 0<candidate.stat().st_size<=2*1024*1024:cover=candidate;break
    if cover:break
   art=hashlib.sha256(cover.read_bytes()).hexdigest() if cover else 'none'
   key=hashlib.sha256(raw+self.revision.encode()+art.encode()+json.dumps([row.get('title'),row.get('version')],ensure_ascii=False).encode()).hexdigest()
   return Plan(key,row,timeline,audio,cover,self.state/(key+'.mp4'))
  except (OSError,ValueError,KeyError,TypeError):return '该谱面暂不支持视频预览，或原曲尚未就绪。'
 def cached(self,plan):
  try:
   if plan.output.is_symlink():return False
   meta=json.loads(plan.output.with_suffix('.json').read_bytes())
   return meta.get('key')==plan.key and plan.output.stat().st_size==meta['bytes'] and 0<meta['bytes']<=30*1024*1024 and hashlib.sha256(plan.output.read_bytes()).hexdigest()==meta['sha256']
  except (OSError,ValueError,KeyError,TypeError):return False
 def start(self,plan):
  if plan.key in self.tasks:return self.tasks[plan.key],False
  if len(self.tasks)>=3:raise ValueError('视频生成队列已满，请稍后重试。')
  task=asyncio.create_task(self.generate(plan));self.tasks[plan.key]=task
  def done(t):
   self.tasks.pop(plan.key,None)
   if not t.cancelled():t.exception()  # Observe failures even after a query has timed out.
  task.add_done_callback(done);return task,True
 async def generate(self,plan):
  async with self.slot:
   if await asyncio.to_thread(self.cached,plan):return plan.output
   work=Path(tempfile.mkdtemp(prefix='job-',dir=self.state));proc=None
   try:
    shutil.copyfile(plan.timeline,work/'chart.json');shutil.copyfile(plan.audio,work/'music.ogg')
    if plan.cover:shutil.copyfile(plan.cover,work/'cover.jpg')
    else:
     from PIL import Image
     Image.new('RGB',(1280,576),'#12212b').save(work/'cover.jpg')
    raw=(work/'chart.json').read_bytes();art=hashlib.sha256((work/'cover.jpg').read_bytes()).hexdigest() if plan.cover else 'none'
    actual=hashlib.sha256(raw+self.revision.encode()+art.encode()+json.dumps([plan.row.get('title'),plan.row.get('version')],ensure_ascii=False).encode()).hexdigest()
    if actual!=plan.key or hashlib.sha256((work/'music.ogg').read_bytes()).hexdigest()!=json.loads(raw)['audio']['sha256']:raise ValueError('Source changed while queued')
    # The renderer reads only these copied public chart assets; never player saves.
    cmd=[sys.executable,'-B',str(self.renderer),'--source',str(work),'--output',str(work/'video.mp4'),'--title',str(plan.row.get('title','')),'--subtitle',str(plan.row.get('version',''))]
    if sys.platform=='linux':cmd=['nice','-n','15']+cmd
    with (work/'render.log').open('wb') as log:
     proc=await asyncio.create_subprocess_exec(*cmd,stdout=log,stderr=log,env={**os.environ,'OPENBLAS_NUM_THREADS':'1','OMP_NUM_THREADS':'1','MALODY_FFMPEG_THREADS':'1'},start_new_session=(sys.platform=='linux'))
     await asyncio.wait_for(proc.wait(),timeout=180)
    video=work/'video.mp4'
    if proc.returncode or not 0<video.stat().st_size<=30*1024*1024:raise ValueError('Video renderer failed or output too large')
    meta={'key':plan.key,'bytes':video.stat().st_size,'sha256':hashlib.sha256(video.read_bytes()).hexdigest()}
    manifest=work/'video.json';manifest.write_text(json.dumps(meta))
    video.replace(plan.output);manifest.replace(plan.output.with_suffix('.json'))
    return plan.output
   finally:
    if proc and proc.returncode is None:
     if sys.platform=='linux':os.killpg(proc.pid,signal.SIGKILL)
     else:proc.kill()
     await proc.wait()
    shutil.rmtree(work,ignore_errors=True)
