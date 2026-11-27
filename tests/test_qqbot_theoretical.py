import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'server/qqbot'))
from theoretical_rating import calculate
from ranking_queries import Queries


class TheoreticalTests(unittest.TestCase):
    def chart(self, cid, level=15, **extra):
        return dict(cid=cid, sid=cid, level=level, version='', title='Song') | extra

    def test_song_dedup_promote_precedence_sc_and_invalid_levels(self):
        rows = [self.chart(1, 14), self.chart(2, 10, sid=1, promote=1),
                self.chart(3, 35), self.chart(4, 2, version='SC14'),
                self.chart(5, 36), self.chart(6, float('nan'))]
        b = calculate(rows)
        self.assertEqual([r['cid'] for r in b['new']], [1])
        self.assertEqual([r['level'] for r in b['old']], [15, 15])
        self.assertEqual(b['invalid_charts'], 2)
        self.assertAlmostEqual(b['rating'], 44 * 1.04 / 30)
        # A hidden search result still participates in the existing rating rules.
        self.assertEqual(len(calculate([self.chart(9, search_visible=False)])['old']), 1)

    def test_caps_zero_slots_and_same_rating_deterministic(self):
        rows = [self.chart(i, promote=i > 30) for i in range(1, 46)]
        b = calculate(rows)
        self.assertEqual(len(b['old']), 20)
        self.assertEqual(len(b['new']), 10)
        self.assertAlmostEqual(b['rating'], 15.6)
        self.assertEqual(b, calculate(reversed(rows)))
        self.assertEqual(calculate([])['rating'], 0)

    def test_rating_command_tracks_catalogue_without_player_files(self):
        base = ROOT / 'build/qqbot-tests'
        base.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as folder:
            catalog = Path(folder) / 'catalog.json'
            catalog.write_text(json.dumps(dict(server='test', charts=[self.chart(1)])), encoding='utf-8')
            q = Queries(Path(folder) / 'no-player-snapshots', catalog)
            for cmd in ['rating', '/RATING', 'rating规则']:
                self.assertIn('理论最高 rating：0.5200', q.execute(cmd))
            catalog.write_text(json.dumps(dict(server='test', charts=[self.chart(1), self.chart(2)])), encoding='utf-8')
            self.assertIn('理论最高 rating：1.0400', q.execute('rating'))
            catalog.write_text('invalid json', encoding='utf-8')
            self.assertIn('当前理论上限暂时无法读取', q.execute('rating'))
            self.assertIn('① 单曲 rating', q.execute('rating'))


if __name__ == '__main__':
    unittest.main()
