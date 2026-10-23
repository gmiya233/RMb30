from io import BytesIO
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server' / 'qqbot'))
from b30_data import prepare, level
from bindings import Bindings
from b30_commands import B30Commands, command_parts
from ranking_queries import Queries


class B30Tests(unittest.TestCase):
    def setUp(self):
        temporary_root = Path(__file__).resolve().parents[1] / 'build' / 'qqbot-tests'
        temporary_root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=temporary_root)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def write(self, path, doc):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(doc), encoding='utf-8')

    def test_song_dedup_promotion_and_java_total_consistency(self):
        charts = [dict(cid=1, sid=1, title='One', level=12), dict(cid=2, sid=1, title='One', promote=1, version='SC9'),
                  dict(cid=3, sid=2, title='Two', level=22), dict(cid=4, sid=3, title='Three', level=10)]
        scores = [dict(cid=i, rating=r, accuracy=99.8, score=100000, judge=3) for i, r in [(1, 12), (2, 13), (3, 11), (4, 10)]]
        p = dict(qq='10001', name='Test Player', best=scores, rating=34/30, o=21/20, n=13/10, oc=2, nc=1)
        result = prepare(p, dict(server='fixture', charts=charts), 'a'*64, 1792700000000)
        self.assertEqual([r['cid'] for r in result['old']], [3, 4])
        self.assertEqual([r['cid'] for r in result['new']], [2])
        self.assertEqual(result['new'][0]['level'], 13)
        self.assertEqual(result['old'][0]['level'], 11)
        self.assertEqual(result['rating'], 34/30)
        p['rating'] += 0.001
        with self.assertRaises(ValueError):
            prepare(p, dict(server='fixture', charts=charts), 'a'*64, 1792700000000)

    def test_levels_match_existing_mapping(self):
        for raw, expected in [(16, 8), (18, 9), (20, 10), (23, 11), (26, 12), (29, 13), (32, 14), (35, 15), (36, None), (16.5, None)]:
            self.assertEqual(level(raw, ''), expected)
        for sc, expected in [(1, 12), (4, 12), (5, 13), (13, 13), (14, 15), (99, 15)]:
            self.assertEqual(level(30, f'SC{sc}'), expected)

    def test_persistent_binding_is_scoped_to_sender_and_group(self):
        path = self.root / 'bindings.db'
        bind = Bindings(path)
        bind.set('group', 'member-secret-identity', '10001')
        self.assertEqual(Bindings(path).get('group', 'member-secret-identity'), '10001')
        self.assertIsNone(bind.get('other-group', 'member-secret-identity'))
        self.assertIsNone(bind.get('group', 'other-member'))
        self.assertNotIn(b'member-secret-identity', path.read_bytes())
        with self.assertRaises(ValueError):
            bind.set('group', 'member-secret-identity', '../10001')
        bind.remove('group', 'member-secret-identity')
        self.assertIsNone(bind.get('group', 'member-secret-identity'))

    def test_missing_cover_uses_background_and_chart_metadata(self):
        from PIL import Image
        from artwork_fallback import supplement
        from publish_catalogue import atomic
        import hashlib
        directory = self.root / 'source' / 'promote' / 'song'
        directory.mkdir(parents=True)
        source = directory / '1.mc'
        source.write_text(json.dumps({'meta': {'cover': '', 'background': 'background.jpg'}}))
        original = source.read_bytes()
        Image.new('RGB', (100, 100), (0, 190, 80)).save(directory / 'background.jpg')
        charts = [dict(cid=1, sid=1, path='promote/song', mc_name='1.mc', title='Test', promote=1)]
        supplement(charts, self.root / 'source', self.root / 'thumbs', atomic)
        digest = hashlib.sha256(b'promote/song/background.jpg').hexdigest()
        self.assertEqual(charts[0]['artwork_fallbacks'], [digest])
        self.assertTrue((self.root / 'thumbs' / (digest + '.jpg')).exists())
        self.assertEqual(source.read_bytes(), original)
        source.write_text(json.dumps({'meta': {'background': 'BACKGROUND.JPG'}}))
        case_chart = dict(cid=3, path='promote/song', mc_name='1.mc')
        supplement([case_chart], self.root / 'source', self.root / 'thumbs', atomic)
        self.assertTrue(case_chart['artwork_fallbacks'])
        p = dict(qq='10001', name='TEST', rating=13/30, o=0, n=1.3, oc=0, nc=1,
                 best=[dict(cid=1, rating=13, accuracy=99.8, score=100000, judge=3)])
        doc = prepare(p, dict(server='fixture', charts=charts), 'a'*64, 1792700000000)
        self.assertEqual(doc['new'][0]['artworks'], [digest])
        charts[0]['cover'] = 'missing.jpg'
        charts[0]['background'] = 'promote/song/background.jpg'
        doc = prepare(p, dict(server='fixture', charts=charts), 'a'*64, 1792700000000)
        self.assertIn(digest, doc['new'][0]['artworks'])
        font = next((p for p in [Path('C:/Windows/Fonts/msyh.ttc'), Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')] if p.exists()), None)
        if font:
            from b30_image import render
            result = Image.open(BytesIO(render(doc, font, self.root / 'thumbs')))
            r, g, b = result.getpixel((75, 1280))
            self.assertGreater(g, r + 100)
        # Unsafe image metadata cannot read or publish a file outside the chart tree.
        source.write_text(json.dumps({'meta': {'background': '../secret.jpg'}}))
        chart = dict(cid=2, path='promote/song', mc_name='1.mc')
        supplement([chart], self.root / 'source', self.root / 'unsafe', atomic)
        self.assertNotIn('artwork_fallbacks', chart)

    def test_b30_unbound_missing_cloud_and_render(self):
        now, revision = 1792700000, 'a'*64
        chart = dict(cid=1, sid=1, title='B30测试曲', version='SC9', level=13, promote=1)
        catalogue = dict(server='fixture', charts=[chart])
        p = dict(qq='10001', name='TEST PLAYER', rating=13/30, o=0, n=1.3, oc=0, nc=1,
                 best=[dict(cid=1, rating=13, accuracy=99.8, score=100000, judge=3)])
        self.write(self.root / 'catalog.json', catalogue)
        self.write(self.root / 'status.json', dict(schema=2, revision=revision))
        self.write(self.root / revision / 'rt.json', dict(schema=2, server='fixture', revision=revision, cid=0, updated=now*1000, players=[p]))
        queries = Queries(self.root, self.root / 'catalog.json', clock=lambda: now)
        font = next((p for p in [Path('C:/Windows/Fonts/msyh.ttc'), Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')] if p.exists()), None)
        commands = B30Commands(queries, self.root / 'bindings.db', self.root / 'b30', font, self.root / 'artwork')
        self.assertIn('首次使用', commands.execute('g', 'u', 'B30'))
        self.assertIn('已设置', commands.execute('g', 'u', '绑定 10001'))
        self.assertIn('更新', commands.execute('g', 'u', 'b30'))
        doc = prepare(p, catalogue, revision, now*1000)
        self.write(self.root / 'b30' / revision / '10001.json', doc)
        if font:
            from PIL import Image
            result = commands.execute('g', 'u', 'b30')
            self.assertIsInstance(result, bytes)
            self.assertEqual(Image.open(BytesIO(result)).size, (1600, 1760))
            preview = Path(__file__).resolve().parents[1] / 'build' / 'qqbot-b30-synthetic.png'
            preview.write_bytes(result)
        doc['qq'] = '10002'
        self.write(self.root / 'b30' / revision / '10001.json', doc)
        self.assertIn('无法生成', commands.execute('g', 'u', 'b30'))
        commands.execute('g', 'u', '绑定 99999')
        self.assertIn('暂无云端成绩', commands.execute('g', 'u', 'b30'))
        self.assertEqual(command_parts('<@!123> /B30'), ('b30', ''))


if __name__ == '__main__':
    unittest.main()
