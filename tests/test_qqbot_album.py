import json
from io import BytesIO
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import urllib.error
from email.message import Message

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'server/qqbot'))
from music_album import album_id,fetch_album,resolve_album_input
from music_share import extract_album
from music_recommendations import MusicRecommendations


def songs(*ids):
    return [dict(mid=i,title=i,artist='Synthetic',album='000BVlhx2z1uI6') for i in ids]


class AlbumTests(unittest.TestCase):
    def test_shared_text_short_link_and_safe_redirect(self):
        value='分享专辑 DJMAX Entertainment《64514》https://c6.y.qq.com/base/fcgi-bin/u?__=5tdtOXAtqfx6 @QQ音乐'
        target='https://i2.y.qq.com/n3/other/pages/details/album.html?albumId=69735318'
        headers=Message();headers['Location']=target
        opener=SimpleNamespace(open=lambda *a,**k:(_ for _ in ()).throw(urllib.error.HTTPError('https://c.y.qq.com/',302,'Found',headers,None)))
        with patch('music_album.urllib.request.build_opener',return_value=opener):
            self.assertEqual(resolve_album_input(value),'69735318')
            self.assertEqual(resolve_album_input(value.replace('https:','https\\:').replace('__','\\_\\_')),'69735318')
            self.assertEqual(extract_album({'url':'https://c6.y.qq.com/base/fcgi-bin/u?__=5tdtOXAtqfx6'}),'https://c.y.qq.com/base/fcgi-bin/u?__=5tdtOXAtqfx6')
            headers.replace_header('Location','https://127.0.0.1/internal')
            with self.assertRaises(ValueError):resolve_album_input(value)
        with patch('music_album.urllib.request.build_opener') as build:
            with self.assertRaises(ValueError):resolve_album_input('分享专辑 https://evil.test/album/1')
            build.assert_not_called()
        with self.assertRaises(ValueError):resolve_album_input('分享专辑：64514 歌手：DJMAX Entertainment')
        self.assertEqual(resolve_album_input('分享专辑 《标题》https://y.qq.com/n/ryqq/albumDetail/AlbumMid @QQ音乐'),'AlbumMid')
        with self.assertRaises(ValueError):resolve_album_input('https://y.qq.com/n/ryqq/albumDetail/A https://y.qq.com/n/ryqq/albumDetail/B')

    def test_official_desktop_mobile_cards_and_rejections(self):
        desktop='https://y.qq.com/n/ryqq_v2/albumDetail/000BVlhx2z1uI6'
        mobile='https://i2.y.qq.com/n3/other/pages/details/album.html?albumid=42&albummid=000BVlhx2z1uI6&platform=11'
        self.assertEqual(album_id(desktop),'000BVlhx2z1uI6')
        self.assertEqual(album_id(mobile),'000BVlhx2z1uI6')
        self.assertEqual(album_id('https://i.y.qq.com/n2/m/share/details/album.html?albumid=42'),'42')
        self.assertEqual(extract_album({'meta':{'music':{'jumpUrl':mobile}}}),'000BVlhx2z1uI6')
        self.assertIsNone(extract_album('分享专辑：64514 歌手：DJMAX Entertainment'))
        self.assertIsNone(extract_album(desktop,'https://y.qq.com/n/ryqq/albumDetail/OtherAlbum'))
        for value in ['https://evil.test/albumDetail/123','https://y.qq.com@evil.test/album/1','https://y.qq.com/n/ryqq/playlist/42','https://i2.y.qq.com/n3/other/pages/details/album.html?id=1&id=2']:
            with self.assertRaises(ValueError):album_id(value)

    def test_complete_album_fetch_only(self):
        d={'code':0,'data':{'name':'Synthetic album','mid':'000BVlhx2z1uI6','total_song_num':2,'list':[
            {'songmid':'a','songname':'A','singer':[{'name':'Artist'}]},
            {'songmid':'b','songname':'B','singer':[{'name':'Artist'}]}]}}
        with patch('music_album.urllib.request.urlopen',side_effect=lambda *a,**k:BytesIO(json.dumps(d).encode())):
            title,rows=fetch_album('000BVlhx2z1uI6')
            self.assertEqual(title,'Synthetic album');self.assertEqual(len(rows),2)
            self.assertEqual(rows[0]['album'],'000BVlhx2z1uI6')
            d['data']['total_song_num']=3
            with self.assertRaises(ValueError):fetch_album('000BVlhx2z1uI6')

    def test_shared_pool_dedup_delivery_restart_and_owner_removal(self):
        base=ROOT/'build/qqbot-tests';base.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as tmp:
            bindings=SimpleNamespace(get=lambda g,m:None if m=='unbound' else '12345')
            args=(Path(tmp)/'music.db',bindings,'unused')
            kwargs=dict(fetcher=lambda p:('Likes',songs('a','b')),album_fetcher=lambda a:('Album',songs('b','c')))
            m=MusicRecommendations(*args,**kwargs)
            self.assertIn('先发送',m.execute('g','unbound','添加专辑 42'))
            m.execute('g','u','绑定歌单 1')
            self.assertIn('已添加',m.execute('g','u','添加专辑 42'))
            m.execute('g','u','添加专辑 42')
            self.assertEqual(set(m.pool()),{'a','b','c'})
            self.assertEqual(m.execute('other','u','我的专辑').count('ID 42'),1)
            seen=set()
            for _ in range(3):
                song=m.pick('g');self.assertNotIn(song['mid'],seen)
                self.assertEqual(m.pick('g')['mid'],song['mid'])
                seen.add(song['mid']);m.delivered('g',song['mid'])
            m=MusicRecommendations(*args,**kwargs)
            self.assertIn(m.pick('g')['mid'],seen) # New cycle after all three songs.
            self.assertIn('没有',m.execute('g','different','移除专辑 42'))
            self.assertIn('已移除',m.execute('other','u','移除专辑 42'))
            self.assertEqual(set(m.pool()),{'a','b'})
            self.assertIn('Likes',m.execute('g','u','我的歌单'))

    def test_pending_album_is_scoped_and_failed_fetch_preserves_sources(self):
        base=ROOT/'build/qqbot-tests';base.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as tmp:
            now=[100.];fail=[False]
            def album(a):
                if fail[0]:raise TimeoutError()
                return 'Album',songs('c')
            m=MusicRecommendations(Path(tmp)/'m.db',SimpleNamespace(get=lambda *a:'12345'),'unused',fetcher=lambda p:('Likes',songs('a')),album_fetcher=album,clock=lambda:now[0])
            msg=SimpleNamespace(content='',share_data={'url':'https://i2.y.qq.com/n3/other/pages/details/album.html?albumid=42'})
            m.execute('g','u','绑定歌单 1');m.execute('g','u','添加专辑 42')
            self.assertIn('10分钟',m.execute('g','u','添加专辑'))
            self.assertEqual(m.binding_input('g','u',msg),'添加专辑 42')
            self.assertIsNone(m.binding_input('other','u',msg))
            self.assertIsNone(m.binding_input('g','other',msg))
            now[0]+=601;self.assertIsNone(m.binding_input('g','u',msg))
            fail[0]=True;self.assertIn('没有添加',m.execute('g','u','添加专辑 99'))
            self.assertEqual(set(m.pool()),{'a','c'})
            now[0]+=21601;self.assertEqual(set(m.pool()),{'a','c'}) # Failed refresh retains last complete cache.

    def test_album_quota_and_replacement(self):
        base=ROOT/'build/qqbot-tests';base.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as tmp:
            m=MusicRecommendations(Path(tmp)/'m.db',SimpleNamespace(get=lambda *a:'12345'),'unused',album_fetcher=lambda a:('Album',songs(a)))
            for i in range(20):self.assertIn('已添加',m.execute('g','u','添加专辑 '+str(i)))
            self.assertIn('20张',m.execute('g','u','添加专辑 99'))
            self.assertIn('已添加',m.execute('g','u','添加专辑 1'))

if __name__=='__main__':unittest.main()
