import sys,json,tempfile,hashlib,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path[:0]=[str(ROOT/'tools/python-libs'),str(ROOT/'server/chart_preview')]
from autoplay import publish_one,confined
class AutoplayTests(unittest.TestCase):
 def test_publish_cache_and_audio(self):
  base=ROOT/'build/autoplay-tests';base.mkdir(parents=True,exist_ok=True)
  with tempfile.TemporaryDirectory(dir=base) as t:
   root=Path(t);song=root/'charts/song';song.mkdir(parents=True);out=root/'output';row={'cid':1,'path':'charts/song','mc_name':'a.mc','server_hash':'current','keys':4,'title':'Fixture'};cat={'server':'https://fixture.invalid'}
   doc={'meta':{'mode':7},'time':[{'beat':[0,0,1],'bpm':120},{'beat':[4,0,1],'bpm':240}],'note':[{'beat':[2,0,1],'x':32,'w':60,'seg':[{'beat':[4,0,1],'x':64}]},{'beat':[0,0,1],'sound':'test.ogg','offset':269}]}
   (song/'a.mc').write_text(json.dumps(doc));(song/'test.ogg').write_bytes(b'synthetic audio')
   self.assertEqual(publish_one(cat,row,root,out),'published');m=json.loads((out/'playback/1.json').read_bytes());self.assertEqual(m['server_hash'],'current');self.assertEqual(m['chart']['audio_start'],.269);self.assertEqual(m['chart']['notes'][0][0],1);self.assertEqual(m['chart']['notes'][0][5][-1],[2.5,96]);self.assertEqual(hashlib.sha256((out/'playback'/m['audio']['file']).read_bytes()).hexdigest(),m['audio']['sha256']);self.assertEqual(publish_one(cat,row,root,out),'unchanged')
   doc['time'][1]['delay']=1;(song/'a.mc').write_text(json.dumps(doc));self.assertEqual(publish_one(cat,row,root,out),'unavailable');self.assertNotIn('chart',json.loads((out/'playback/1.json').read_bytes()))
 def test_path_escape(self):
  with self.assertRaises(ValueError):confined(ROOT,'charts/song','../../../secret')
if __name__=='__main__':unittest.main()
