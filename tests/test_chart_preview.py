"""Synthetic Slide fixtures only; never read player snapshots."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / 'server/chart_preview'), str(ROOT / 'server/qqbot')]
from render import parse, render, beat
from publish import publish, source_file
from chart_preview import ChartPreviews

FONT = next((p for p in [Path('C:/Windows/Fonts/msyh.ttc'), Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')] if p.exists()), None)


class PreviewTests(unittest.TestCase):
    def data(self):
        return json.dumps({'meta': {'mode': 7}, 'time': [{'beat': [0, 0, 1], 'bpm': 120}, {'beat': [32, 0, 1], 'bpm': 180}], 'note': [
            {'beat': [0, 0, 1], 'x': 31, 'w': 64},
            {'beat': [1, 0, 1], 'x': 159, 'w': 61, 'dir': 8},
            {'beat': [30, 0, 1], 'x': 31, 'w': 64, 'seg': [{'beat': [4, 0, 1], 'x': 64}, {'beat': [8, 0, 1], 'x': 128}]},
            {'beat': [2, 0, 1], 'sound': 'audio.ogg'}]}).encode()

    def temporary(self):
        base = ROOT / 'build/preview-tests'; base.mkdir(parents=True, exist_ok=True)
        return tempfile.TemporaryDirectory(dir=base)

    def test_offsets_and_flags(self):
        chart = parse(self.data(), 4)
        self.assertEqual(chart.keys, 4); self.assertEqual(len(chart.notes), 3)
        self.assertEqual(chart.notes[-1].points, ((30, 31), (34, 95), (38, 159)))
        self.assertEqual(chart.notes[1].direction, 8)
        self.assertEqual(chart.columns, 4)
        self.assertEqual(beat([3, 1, 4]), 3.25)
        self.assertAlmostEqual(beat([5, -1, 384]), 5 - 1 / 384)
        self.assertEqual(beat([0, 4, 4]), 1)
        for bad in [[0, 1, 0], [0, 1, -4], [0, 1000001, 4], [True, 0, 1]]:
            with self.assertRaises(ValueError): beat(bad)

    def test_adaptive_spacing_uses_bpm_and_subdivision(self):
        dense = json.loads(self.data())
        dense['note'] = [{'beat': [i // 32, i % 32, 32], 'x': 31, 'w': 60} for i in range(33)]
        slow = parse(self.data(), 4)
        fine = parse(json.dumps(dense).encode(), 4)
        self.assertGreater(fine.spacing['rate'], slow.spacing['rate'])
        self.assertLessEqual(fine.spacing['height'], 3600)
        self.assertGreaterEqual(fine.spacing['per_page'], 1)
        self.assertEqual(slow.spacing['clock'].seconds(1) - slow.spacing['clock'].seconds(0), 0.5)
        self.assertAlmostEqual(slow.spacing['clock'].seconds(33) - slow.spacing['clock'].seconds(32), 1 / 3)

    @unittest.skipUnless(FONT, 'CJK font required')
    def test_hold_is_orthogonal_with_terminal_arrow_only(self):
        from PIL import Image
        from io import BytesIO
        data = {'meta': {'mode': 7}, 'time': [{'beat': [0, 0, 1], 'bpm': 120}], 'note': [
            {'beat': [0, 0, 1], 'x': 31, 'w': 60, 'seg': [{'beat': [4, 0, 1], 'x': 128}, {'beat': [6, 0, 1], 'x': 64}]},
            {'beat': [8, 0, 1], 'x': 95, 'w': 61, 'dir': 2}]}
        chart = parse(json.dumps(data).encode(), 4)
        png, _ = render(chart, {'cid': 1}, FONT); image = Image.open(BytesIO(png))
        def green(x, y):
            pixels = image.crop((round(x)-2, round(y)-2, round(x)+3, round(y)+3)).get_flattened_data()
            return any(g > 150 and g > r*1.15 and g > b*1.15 for r, g, b in pixels)
        def location(t, x): return 24+68+x/255*216, 180+8+chart.spacing['height']-t*150
        self.assertTrue(green(*location(2, 31)), 'hold stays in its original lane')
        self.assertFalse(green(*location(2, 95)), 'no diagonal interpolation through another lane')
        x, y = location(4, 159)
        self.assertFalse(green(x-13, y-9), 'intermediate bend has no arrow')
        x, y = location(6, 95)
        self.assertTrue(green(x+13, y-9), 'final left flick has an open chevron')
        x, y = location(8, 95)
        self.assertTrue(green(x+34, y), 'standalone shaft leaves the rectangle edge')

    def test_invalid_note_does_not_silently_disappear(self):
        doc = json.loads(self.data()); doc['note'][0]['x'] = 500
        with self.assertRaises(ValueError): parse(json.dumps(doc).encode())
        doc = json.loads(self.data()); del doc['note'][0]['w']
        self.assertEqual(parse(json.dumps(doc).encode(), 4).keys, 4)
        doc = json.loads(self.data()); doc['note'][2]['seg'][1]['beat'] = [1, 0, 1]
        with self.assertRaises(ValueError): parse(json.dumps(doc).encode())
        doc = json.loads(self.data()); doc['meta']['mode'] = 0
        with self.assertRaises(ValueError): parse(json.dumps(doc).encode())

    @unittest.skipUnless(FONT, 'CJK font required')
    def test_render_and_incremental_publication(self):
        chart = parse(self.data(), 4)
        png, size = render(chart, {'cid': 1, 'title': '合成测试', 'version': '4K LV.12'}, FONT)
        self.assertTrue(png.startswith(b'\x89PNG')); self.assertLess(len(png), 4194304)
        self.assertLessEqual(size[0] * size[1], 16000000)
        with self.temporary() as temp:
            root = Path(temp); source = root / 'charts'; source.mkdir(); (source / '1.mc').write_bytes(self.data())
            row = {'cid': 1, 'server_hash': 'h', 'path': 'charts', 'mc_name': '1.mc', 'title': '合成测试', 'keys': 4}
            catalogue = {'server': 'https://fixture.invalid', 'charts': [row]}
            output, state = root / 'output', root / 'state'
            self.assertEqual(publish(catalogue, root, output, state, FONT)['rendered'], 1)
            manifest = json.loads((output / '1.json').read_bytes())
            first = manifest['pages'][0]; self.assertEqual(first['sha256'], hashlib.sha256((output / first['image']).read_bytes()).hexdigest())
            self.assertEqual(publish(catalogue, root, output, state, FONT)['unchanged'], 1)
            row['version'] = 'LV.13'
            self.assertEqual(publish(catalogue, root, output, state, FONT)['rendered'], 1)
            (source / '1.mc').unlink()
            self.assertEqual(publish(catalogue, root, output, state, FONT)['unavailable'], 1)
            self.assertEqual(json.loads((output / '1.json').read_bytes())['status'], 'unavailable')
            row['path'] = '../outside'
            with self.assertRaises(ValueError): source_file(root, row)

    def test_bot_scope_and_digest(self):
        with self.temporary() as temp:
            root = Path(temp); data = b'\x89PNG\r\n\x1a\n' + b'synthetic'; digest = hashlib.sha256(data).hexdigest(); image = f'1-{digest}.png'; (root / image).write_bytes(data)
            row = {'cid': 1, 'server_hash': 'h', 'title': 'Test', 'version': '4K LV.12'}
            class Queries:
                def catalogue(self): return 's', {1: row}
                def search(self, arg): return [row] if arg == 'Test' else []
            doc = {'schema': 1, 'server': 's', 'cid': 1, 'server_hash': 'h', 'status': 'ready', 'pages': [{'image': image, 'sha256': digest, 'bytes': len(data)}]}
            (root / '1.json').write_text(json.dumps(doc))
            bot = ChartPreviews(Queries(), root)
            self.assertEqual(bot.execute('谱面预览 1'), data); self.assertEqual(bot.execute('谱面预览 Test'), data)
            self.assertIn('共 1 页', bot.execute('谱面预览 1 2'))
            (root / image).write_bytes(b'corrupt'); self.assertIsInstance(bot.execute('谱面预览 1'), str)
            row['search_visible'] = False; self.assertIn('没有找到', bot.execute('谱面预览 1'))
            row['search_visible'] = True; row['server_hash'] = 'new'; self.assertIsInstance(bot.execute('谱面预览 1'), str)


if __name__ == '__main__': unittest.main()
