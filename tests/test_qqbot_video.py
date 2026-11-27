import asyncio,hashlib,json,sys,tempfile,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server/qqbot'))
from chart_video import ChartVideos, VideoCooldown
from video_upload import upload_video

class Queries:
 def __init__(self):self.rows={123:{'cid':123,'title':'Song','version':'LV.13','server_hash':'hash','search_visible':True}}
 def catalogue(self):return 'server',self.rows
 def search(self,arg):return list(self.rows.values()) if arg=='Song' else []

class VideoTests(unittest.IsolatedAsyncioTestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.queries=Queries()
  (self.root/'revision.txt').write_text('skin1');self.audio=b'OggS'+b'a'*100;name=hashlib.sha256(self.audio).hexdigest()+'.ogg';(self.root/name).write_bytes(self.audio)
  self.doc={'schema':1,'server':'server','cid':123,'server_hash':'hash','audio':{'file':name,'sha256':hashlib.sha256(self.audio).hexdigest()},'chart':{}}
  self.write_doc();self.videos=ChartVideos(self.queries,self.root,[],self.root/'cache',self.root/'render.py')
 def tearDown(self):self.tmp.cleanup()
 def write_doc(self):(self.root/'123.json').write_text(json.dumps(self.doc))
 def test_identity_cache_and_corruption(self):
  p=self.videos.resolve('谱面视频 123');self.assertFalse(self.videos.cached(p));data=b'video';p.output.write_bytes(data);p.output.with_suffix('.json').write_text(json.dumps({'key':p.key,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}));self.assertTrue(self.videos.cached(p))
  p.output.write_bytes(b'wrong');self.assertFalse(self.videos.cached(p));self.doc['revision']='new chart';self.write_doc();self.assertNotEqual(self.videos.resolve('谱面视频 123').key,p.key)
  self.videos.revision='skin2';self.assertNotEqual(self.videos.resolve('谱面视频 123').key,p.key)
 def test_hidden_and_wrong_namespace(self):
  self.queries.rows[123]['search_visible']=False;self.assertIsInstance(self.videos.resolve('谱面视频 123'),str)
  self.queries.rows[123]['search_visible']=True;self.doc['server']='other';self.write_doc();self.assertIsInstance(self.videos.resolve('谱面视频 123'),str)
 async def test_duplicate_queries_share_job(self):
  calls=[]
  async def fake(plan):calls.append(plan.key);await asyncio.sleep(.02);return plan.output
  self.videos.generate=fake;p=self.videos.resolve('谱面视频 Song');a,first=self.videos.start(p);b,second=self.videos.start(p);self.assertTrue(first);self.assertFalse(second);self.assertIs(a,b);await asyncio.gather(a,b);self.assertEqual(len(calls),1)
 async def test_global_cooldown_persisted_and_cached_bypass(self):
  now=[1000.0];self.videos.clock=lambda:now[0]
  async def fake(plan):await asyncio.sleep(.001);return plan.output
  self.videos.generate=fake;p=self.videos.resolve('谱面视频 123');task,_=self.videos.start(p);await task;await asyncio.sleep(0)
  self.doc['revision']='different';self.write_doc();other=self.videos.resolve('谱面视频 123')
  with self.assertRaisesRegex(VideoCooldown,'小师妹打歌有点累了'):self.videos.start(other)
  restarted=ChartVideos(self.queries,self.root,[],self.root/'cache',self.root/'render.py',clock=lambda:now[0])
  with self.assertRaises(VideoCooldown):restarted.start(other)
  data=b'cached';other.output.write_bytes(data);other.output.with_suffix('.json').write_text(json.dumps({'key':other.key,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}))
  ready,first=restarted.start(other);self.assertFalse(first);self.assertEqual(await ready,other.output);self.assertEqual(restarted.last_started,1000)
  now[0]=1299.99
  with self.assertRaises(VideoCooldown):self.videos.start(p)
  now[0]=1300;task,first=self.videos.start(p);self.assertTrue(first);await task
 async def test_multipart_exact_bytes_and_confirmations(self):
  p=self.root/'video.mp4';data=b'abcdefghij';p.write_bytes(data);chunks=[];requests=[]
  async def req(endpoint,payload):
   requests.append((endpoint,payload))
   if endpoint=='upload_prepare':return {'upload_id':'u','block_size':'4','parts':[{'index':i,'presigned_url':f'https://storage.example/{i}'} for i in range(3)]}
   if endpoint=='files':return {'file_info':'media','ttl':300}
   return {}
  async def put(url,chunk):chunks.append(chunk)
  result=await upload_video(req,put,p);self.assertEqual(b''.join(chunks),data);self.assertEqual([x[1]['block_size'] for x in requests if x[0]=='upload_part_finish'],['4','4','2']);self.assertEqual(result['file_info'],'media');self.assertFalse(requests[-1][1]['srv_send_msg'])
 async def test_live_one_based_numbering(self):
  p=self.root/'video.mp4';p.write_bytes(b'123456789');chunks=[];confirmed=[]
  async def req(endpoint,payload):
   if endpoint=='upload_prepare':return {'upload_id':'u','block_size':'5','parts':[{'index':i,'presigned_url':f'https://storage.example/{i}'} for i in [1,2]]}
   if endpoint=='upload_part_finish':confirmed.append(payload['part_index']);return {}
   return {'file_info':'ready'}
  async def put(url,chunk):chunks.append(chunk)
  await upload_video(req,put,p);self.assertEqual(b''.join(chunks),b'123456789');self.assertEqual(confirmed,[1,2])
 async def test_invalid_multipart_does_not_put(self):
  p=self.root/'video.mp4';p.write_bytes(b'12345678')
  async def req(*a):return {'upload_id':'u','block_size':'4','parts':[{'index':2,'presigned_url':'https://storage.example'}]}
  async def put(*a):self.fail('Invalid parts must not be uploaded')
  with self.assertRaises(ValueError):await upload_video(req,put,p)

if __name__=='__main__':unittest.main()
