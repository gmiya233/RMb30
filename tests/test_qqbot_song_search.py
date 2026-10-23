from io import BytesIO
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server' / 'qqbot'))
from ranking_queries import Queries
from song_search import SongSearch, load_rankings
from PIL import Image


class SongSearchTests(unittest.TestCase):
    def setUp(self):
        root = Path(__file__).resolve().parents[1] / 'build' / 'qqbot-tests'
        root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=root)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.catalogue = self.root / 'catalog.json'
        self.font = next((p for p in [Path('C:/Windows/Fonts/msyh.ttc'), Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')] if p.exists()), None)
        if not self.font:
            self.skipTest('CJK font required for rendering tests')
        self.queries = Queries(self.root, self.catalogue)
        self.search = SongSearch(self.queries, self.font, [self.root / 'art'])

    def write(self, charts):
        self.catalogue.write_text(json.dumps(dict(server='fixture', charts=charts)), encoding='utf-8')

    def chart(self, cid=1, sid=1, **extra):
        return dict(cid=cid, sid=sid, title='测试歌曲', artist='Test Artist', creator='Test Mapper',
                    version='SC9', level=13, bpm=180, search_visible=True) | extra

    def test_single_song_multiple_difficulties_renders_with_art(self):
        digest = 'a' * 64
        art = self.root / 'art'
        art.mkdir()
        Image.new('RGB', (384, 384), (200, 40, 80)).save(art / (digest + '.jpg'))
        self.write([self.chart(artworks=[digest]), self.chart(cid=2, version='SC14', artworks=[digest])])
        result = self.search.execute('<@!123> 查歌 测试歌曲')
        self.assertIsInstance(result, bytes)
        image = Image.open(BytesIO(result))
        self.assertEqual(image.size, (1200, 877))
        self.assertGreater(image.getpixel((100, 100))[0], 180)
        (Path(__file__).resolve().parents[1] / 'build' / 'qqbot-song-search-synthetic.png').write_bytes(result)

    def test_same_title_different_songs_stays_text(self):
        self.write([self.chart(), self.chart(cid=2, sid=2)])
        result = self.search.execute('查歌 测试歌曲')
        self.assertIsInstance(result, str)
        self.assertIn('2 张谱面', result)

    def test_hidden_results_do_not_prevent_unique_song(self):
        self.write([self.chart(), self.chart(cid=2, sid=2, search_visible=False)])
        self.assertIsInstance(self.search.execute('查歌 测试歌曲'), bytes)
        self.assertIsInstance(self.search.execute('查歌 missing'), str)

    def test_missing_art_and_unknown_song_ids_remain_safe(self):
        self.write([self.chart(sid=0, artworks=['../unsafe'])])
        self.assertIsInstance(self.search.execute('查歌 测试歌曲'), bytes)
        self.write([self.chart(sid=0), self.chart(cid=2, sid=0)])
        self.assertIsInstance(self.search.execute('查歌 测试歌曲'), str)
        self.assertEqual(self.search.execute('查歌'), '用法：查歌 歌名')

    def test_rankings_are_separate_sorted_and_bounded(self):
        from unittest.mock import patch
        self.write([self.chart(), self.chart(cid=2, version='SC14')])
        revision = 'a' * 64
        (self.root / revision / 'charts').mkdir(parents=True)
        (self.root / 'status.json').write_text(json.dumps(dict(schema=2, revision=revision)))
        for cid in (1, 2):
            players = [dict(qq=str(10000+i), name=f'Player {i}', best=[dict(cid=cid, rating=12 if i < 2 else 11,
                        accuracy=99.8, score=100000, judge=5)]) for i in range(8 if cid == 1 else 0)]
            (self.root / revision / 'charts' / f'{cid}.json').write_text(json.dumps(dict(
                schema=2, server='fixture', revision=revision, cid=cid, updated=1792700000000, players=players)))
        boards = load_rankings(self.queries, self.queries.search('测试歌曲'))
        self.assertEqual(len(boards[1]['rows']), 5)
        self.assertEqual(boards[1]['count'], 8)
        self.assertEqual([r['rank'] for r in boards[1]['rows']][:3], [1, 1, 3])
        self.assertEqual(boards[1]['rows'][0]['judge'], 5)
        self.assertEqual(boards[2]['rows'], [])
        with patch('song_search.render', wraps=__import__('song_search').render) as renderer:
            result = self.search.execute('查歌 测试歌曲')
            self.assertIsInstance(result, bytes)
            passed = renderer.call_args.args[3]
            self.assertEqual(passed[1]['count'], 8)
            self.assertEqual(passed[2]['count'], 0)
            (Path(__file__).resolve().parents[1] / 'build' / 'qqbot-song-ranking-synthetic.png').write_bytes(result)


if __name__ == '__main__':
    unittest.main()
