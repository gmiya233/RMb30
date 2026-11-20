import json
from pathlib import Path
import sys
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'server'))
from song_metadata import apply,supplement

class MetadataTests(unittest.TestCase):
    def test_original_priority_independent_fallback_and_search_names(self):
        row=dict(title='数据库名',artist='数据库歌手')
        apply(row,dict(titleorg='原名',title='译名',artistorg=' ',artist='歌手'))
        self.assertEqual((row['title'],row['artist']),('原名','歌手'))
        self.assertIn('译名',row['search_names']);self.assertIn('数据库名',row['search_names'])
        apply(row,{})
        self.assertEqual((row['title'],row['artist']),('原名','歌手'))

    def test_cache_refresh_and_read_only_source(self):
        base=ROOT/'build/metadata-tests';base.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as temp:
            folder=Path(temp);song=folder/'charts';song.mkdir();source=song/'test.mc'
            source.write_text(json.dumps(dict(meta=dict(song=dict(titleorg='Original',artistorg='Artist')))),encoding='utf-8')
            raw=source.read_bytes();cache=folder/'cache.json'
            def row():return dict(cid=1,path='charts',mc_name='test.mc',title='DB',artist='DB artist')
            first=row();supplement(folder/'malody.db',[first],cache)
            self.assertEqual(first['title'],'Original');self.assertEqual(source.read_bytes(),raw)
            second=row();supplement(folder/'malody.db',[second],cache);self.assertEqual(first,second)
            source.write_text(json.dumps(dict(meta=dict(song=dict(titleorg='New Original')))),encoding='utf-8')
            third=row();supplement(folder/'malody.db',[third],cache)
            self.assertEqual(third['title'],'New Original');self.assertEqual(third['artist'],'DB artist')

if __name__=='__main__':unittest.main()
