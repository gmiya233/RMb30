import json
from pathlib import Path
import sys
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'server/qqbot'))
from song_aliases import SongAliases
from ranking_queries import Queries

class AliasTests(unittest.TestCase):
    def test_persistent_shared_song_alias_search_and_validation(self):
        folder=ROOT/'build/qqbot-tests';folder.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=folder) as name:
            root=Path(name);catalog=root/'catalog.json'
            catalog.write_text(json.dumps(dict(server='test',charts=[
                dict(cid=1,sid=100,title='Test',search_visible=True),
                dict(cid=2,sid=100,title='Test',search_visible=True),
                dict(cid=3,sid=101,title='Hidden',search_visible=False)])),encoding='utf-8')
            q=Queries(root,catalog);q.alias_store=SongAliases(root/'aliases.sqlite3')
            self.assertIn('已添加',q.execute('/歌曲别名 1 Test Alias'))
            self.assertIn('已经记录',q.execute('/歌曲别名 2 test alias'))
            self.assertEqual(len(q.search('TEST ALIAS')),2)
            self.assertEqual(q.catalogue()[1][2]['aliases'],['Test Alias'])
            self.assertIn('已添加',q.execute('歌曲别名 100 别名二'))
            self.assertIn('不在',q.execute('歌曲别名 3 不可见'))
            self.assertIn('不在',q.execute('歌曲别名 999 缺失'))
            self.assertIn('不能',q.execute('歌曲别名 1 @everyone'))
            self.assertIn('48',q.execute('歌曲别名 1 '+'a'*49))
            self.assertIn('用法',q.execute('歌曲别名 1'))
            reopened=SongAliases(root/'aliases.sqlite3')
            self.assertEqual(reopened.all()['sid:100'],['Test Alias','别名二'])
            self.assertEqual(q.search('不可见'),[])

if __name__=='__main__': unittest.main()
