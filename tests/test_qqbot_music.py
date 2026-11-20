import json,sqlite3,tempfile,sys,unittest
from pathlib import Path
from types import SimpleNamespace
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'server/qqbot'))
from music_recommendations import MusicRecommendations,playlist_id
from recommendations import Recommendations,INTERVAL
from music_share import extract_playlist

class MusicTests(unittest.TestCase):
 def test_bind_dedup_rotation_delivery_restart_and_unbind(self):
  base=ROOT/'build/qqbot-tests';base.mkdir(parents=True,exist_ok=True)
  with tempfile.TemporaryDirectory(dir=base) as tmp:
   bindings=SimpleNamespace(get=lambda group,member:'12345' if member!='unbound' else None)
   def fetch(pid):return 'Likes',[dict(mid=x,title=x,artist='Test',album='') for x in ({'1':['a','b'],'2':['b','c'],'3':['d']}[pid])]
   store=MusicRecommendations(Path(tmp)/'music.db',bindings,'unused',fetcher=fetch)
   self.assertIn('先发送',store.execute('g','unbound','绑定歌单 1'))
   for owner,pid in [('a','1'),('b','2')]:self.assertIn('已绑定',store.execute('g',owner,'绑定歌单 '+pid))
   self.assertEqual(len(store.pool()),3)
   seen=set()
   for i in range(3):
    s=store.pick('g');self.assertNotIn(s['mid'],seen);self.assertEqual(store.pick('g')['mid'],s['mid'])
    store.delivered('g',s['mid']);seen.add(s['mid'])
   store=MusicRecommendations(Path(tmp)/'music.db',bindings,'unused',fetcher=fetch)
   store.execute('g','c','绑定歌单 3');self.assertEqual(store.pick('g')['mid'],'d');store.delivered('g','d')
   self.assertIn(store.pick('g')['mid'],{'a','b','c','d'})
   self.assertIn('已解绑',store.execute('g','c','解绑歌单'));self.assertNotIn('d',store.pool())
 def test_official_url_only(self):
  self.assertEqual(playlist_id('https://y.qq.com/n/ryqq_v2/playlist/3191965433?foo=1'),'3191965433')
  mobile='https://i2.y.qq.com/n3/other/pages/details/playlist.html?platform=11&hosteuin=masked**&id=7961262342'
  self.assertEqual(playlist_id(mobile),'7961262342')
  self.assertEqual(extract_playlist({'meta':{'music':{'jumpUrl':mobile}}}),'7961262342')
  self.assertEqual(extract_playlist(json.dumps({'url':mobile})),'7961262342')
  self.assertIsNone(extract_playlist(mobile,'https://y.qq.com/n/ryqq_v2/playlist/123'))
  self.assertIsNone(extract_playlist('分享歌单:我喜欢'))
  for value in ['https://evil.test/playlist/123','http://127.0.0.1/','https://y.qq.com@evil.test/playlist/1']:
   with self.assertRaises(ValueError):playlist_id(value)
 def test_pending_share_binding_is_scoped_and_expires(self):
  base=ROOT/'build/qqbot-tests';base.mkdir(parents=True,exist_ok=True)
  with tempfile.TemporaryDirectory(dir=base) as tmp:
   now=[100.];store=MusicRecommendations(Path(tmp)/'m.db',SimpleNamespace(get=lambda *a:'12345'),'unused',fetcher=lambda pid:('Likes',[dict(mid='abc',title='A',artist='B',album='')]),clock=lambda:now[0])
   msg=SimpleNamespace(content='',share_data={'fields':[{'url':'https://i2.y.qq.com/n3/other/pages/details/playlist.html?id=7961262342'}]})
   self.assertIsNone(store.binding_input('g','u',msg))
   self.assertIn('10分钟',store.execute('g','u','绑定歌单'))
   self.assertEqual(store.binding_input('g','u',msg),'绑定歌单 7961262342')
   self.assertIsNone(store.binding_input('other','u',msg))
   now[0]+=601;self.assertIsNone(store.binding_input('g','u',msg))
 def test_hourly_persistent_alternation_and_quiet_hours(self):
  base=ROOT/'build/qqbot-tests';base.mkdir(parents=True,exist_ok=True)
  with tempfile.TemporaryDirectory(dir=base) as tmp:
   now=[1794448800.] # All test times chosen away from quiet hours below.
   from datetime import datetime
   from zoneinfo import ZoneInfo
   now[0]=datetime(2026,11,12,12,tzinfo=ZoneInfo('Asia/Shanghai')).timestamp()
   args=(None,'unused',[],Path(tmp)/'r.db')
   r=Recommendations(*args,clock=lambda:now[0]);r.enable('g')
   self.assertEqual(INTERVAL,3600)
   now[0]+=3600;self.assertEqual(r.due(set()),['g']);self.assertEqual(r.kind('g'),'music')
   r=Recommendations(*args,clock=lambda:now[0]);now[0]+=3600;r.due(set());self.assertEqual(r.kind('g'),'chart')
   now[0]=datetime(2026,11,13,2,tzinfo=ZoneInfo('Asia/Shanghai')).timestamp();self.assertEqual(r.due(set()),[])

if __name__=='__main__':unittest.main()
