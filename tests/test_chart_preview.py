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

    def test_single_sheet_and_slow_bpm_do_not_stretch(self):
        doc = json.loads(self.data())
        normal = parse(json.dumps(doc).encode(), 4)
        doc['time'] = [{'beat': [0, 0, 1], 'bpm': 1}, {'beat': [12, 0, 1], 'bpm': 200}]
        slow = parse(json.dumps(doc).encode(), 4)
        self.assertEqual(normal.spacing['height'], slow.spacing['height'])
        self.assertEqual(normal.spacing['rate'], slow.spacing['rate'])
        doc['note'].append({'beat': [4096, 0, 1], 'x': 31, 'w': 60})
        long = parse(json.dumps(doc).encode(), 4)
        self.assertEqual(long.pages, 1)
        self.assertLessEqual(long.columns, 20)
        self.assertGreater(long.first + long.columns * long.beats_per_column, long.end)
        self.assertLessEqual((long.columns * 376 + 40) * (long.spacing['height'] + 262), 16000000)

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
        def location(t, x): return 24+68+x/255*216, 180+8+chart.spacing['height']-t*chart.spacing['rate']
        self.assertTrue(green(*location(2, 31)), 'hold stays in its original lane')
        self.assertFalse(green(*location(2, 95)), 'no diagonal interpolation through another lane')
        x, y = location(4, 159)
        self.assertFalse(green(x-13, y-9), 'intermediate bend has no arrow')
        x, y = location(6, 95)
        self.assertTrue(green(x+13, y-9), 'final left flick has an open chevron')
        x, y = location(8, 95)
        self.assertTrue(green(x+34, y), 'standalone shaft leaves the rectangle edge')
        self.assertTrue(green(x+54, y), 'single flick reaches its target lane')
        self.assertFalse(green(x+77, y), 'single flick must not overshoot its target by half a key')
        from render import rectangular_head
        self.assertEqual(rectangular_head('4note1.png',51,14).getchannel('A').getextrema(),(255,255))

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

    def test_imd_binary_records_and_chain_geometry(self):
        import struct
        from imd import parse_imd
        def binary(rows):
            return struct.pack('<IIId', 9000, 1, 0, 120.0) + b'\x03\x03' + struct.pack('<I', len(rows)) + b''.join(struct.pack('<BBIBi', kind,0,time,lane,value) for kind,time,lane,value in rows)
        rows = [(0,0,0,0),(1,500,2,-2),(2,1000,1,500),
                (0x62,2000,0,500),(0x21,2500,0,2),(0x22,3000,2,500),(0xa1,3500,2,-1),
                (0x61,4000,1,1),(0xa2,4500,2,500)]
        chart = parse_imd(binary(rows),4)
        self.assertEqual(len(chart.notes),5)
        self.assertEqual(chart.notes[1].direction,8)
        self.assertEqual(chart.notes[1].width,62)
        self.assertEqual(chart.notes[2].end,3)
        self.assertEqual(chart.notes[3].points,((4,32),(5,32),(5,160),(6,160),(7,160),(7,96)))
        self.assertEqual(chart.notes[4].points,((8,96),(8,160),(9,160),(10,160)))
        for bad in [binary(rows)[:-1], binary([(0x21,0,0,1)]), binary([(1,0,0,-1)]), binary([(0x62,0,0,500)])]:
            with self.assertRaises(ValueError): parse_imd(bad,4)

    @unittest.skipUnless(FONT, 'CJK font required')
    def test_publisher_prefers_exact_imd_and_tracks_changes(self):
        import struct
        def binary(lane):
            return struct.pack('<IIId',9000,1,0,120.) + b'\x03\x03' + struct.pack('<I',1) + struct.pack('<BBIBi',1,0,500,lane,-1)
        with self.temporary() as temp:
            root=Path(temp); folder=root/'charts';folder.mkdir()
            (folder/'1.mc').write_bytes(self.data())
            original=folder/'Exact Song.imd';original.write_bytes(binary(2))
            row=dict(cid=1,server_hash='h',path='charts',mc_name='1.mc',title='Exact Song',keys=4)
            catalogue=dict(server='fixture',charts=[row]);out=root/'out';state=root/'state'
            self.assertEqual(publish(catalogue,root,out,state,FONT)['rendered'],1)
            first=json.loads((out/'1.json').read_bytes())
            self.assertEqual(first['notes'],1)
            self.assertEqual(first['source_sha256'],hashlib.sha256(original.read_bytes()).hexdigest())
            original.write_bytes(binary(3))
            self.assertEqual(publish(catalogue,root,out,state,FONT)['rendered'],1)
            self.assertNotEqual(first['source_sha256'],json.loads((out/'1.json').read_bytes())['source_sha256'])
            original.unlink()
            self.assertEqual(publish(catalogue,root,out,state,FONT)['rendered'],1)
            self.assertEqual(json.loads((out/'1.json').read_bytes())['notes'],3)

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
            self.assertIn('完整单图', bot.execute('谱面预览 1 2'))
            (root / image).write_bytes(b'corrupt'); self.assertIsInstance(bot.execute('谱面预览 1'), str)
            row['search_visible'] = False; self.assertIn('没有找到', bot.execute('谱面预览 1'))
            row['search_visible'] = True; row['server_hash'] = 'new'; self.assertIsInstance(bot.execute('谱面预览 1'), str)


if __name__ == '__main__': unittest.main()
