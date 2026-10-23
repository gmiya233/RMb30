import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server' / 'qqbot'))
from ranking_queries import Queries
from bot import Gate
from publish_catalogue import public_catalogue


class BotTests(unittest.TestCase):
    def setUp(self):
        temporary_root = Path(__file__).resolve().parents[1] / 'build' / 'qqbot-tests'
        temporary_root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=temporary_root)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.revision = 'a' * 64
        self.directory = self.root / self.revision
        (self.directory / 'charts').mkdir(parents=True)
        self.now = 1792700000
        self.write(self.root / 'catalog.json', {'server': 'fixture', 'charts': [
            {'cid': 1, 'title': 'Test Song', 'version': 'SC9', 'artist': 'Test Artist', 'creator': 'Test Mapper'},
            {'cid': 2, 'title': 'Test Song', 'version': 'SC14'}]})
        self.write(self.root / 'status.json', {'schema': 2, 'revision': self.revision})
        self.players = [self.player('10002', 'Beta', 12), self.player('10001', 'Alpha', 12), self.player('10003', 'Gamma', 11)]
        self.publish()
        self.query = Queries(self.root, self.root / 'catalog.json', clock=lambda: self.now)

    def write(self, path, value):
        path.write_text(json.dumps(value), encoding='utf-8')

    def player(self, qq, name, rating):
        return dict(qq=qq, name=name, rating=rating, o=rating, n=rating, oc=20, nc=10, best=[])

    def publish(self, cid=0, players=None, **extra):
        path = self.directory / ('rt.json' if not cid else f'charts/{cid}.json')
        self.write(path, dict(schema=2, server='fixture', revision=self.revision, cid=cid,
                              updated=self.now * 1000, failed=0, players=self.players if players is None else players) | extra)

    def test_total_competition_ties_and_lookup(self):
        result = self.query.execute('<@!123> /总榜')
        self.assertLess(result.index('1. Alpha'), result.index('1. Beta'))
        self.assertIn('3. Gamma', result)
        self.assertNotIn('10001', result)
        result = self.query.execute('查排名 10002')
        self.assertIn('第1名', result)
        self.assertIn('12.0000', result)
        self.assertIn('没有这个QQ', self.query.execute('查排名 99999'))
        self.assertIn('有效页码', self.query.execute('总榜 2'))
        self.assertIn('页码', self.query.execute('总榜 -1'))

    def test_single_score_order_and_same_rank(self):
        rows = [self.player('10001', 'Alpha', 12), self.player('10002', 'Beta', 12), self.player('10003', 'Gamma', 12)]
        for index, row in enumerate(rows):
            row['best'] = [dict(cid=1, rating=13, accuracy=99.8 if index else 99.7, score=100000, judge=1)]
        self.publish(cid=1, players=rows)
        result = self.query.execute('单曲榜 1')
        self.assertLess(result.index('1. Beta'), result.index('3. Alpha'))
        self.assertIn('1. Gamma', result)
        self.assertIn('A判', result)
        self.assertIn('99.800%', result)

    def test_search_does_not_pick_ambiguous_chart(self):
        result = self.query.execute('单曲榜 test song')
        self.assertIn('2 张谱面', result)
        self.assertIn('SC14', result)
        self.assertIn('Test Mapper', self.query.execute('查歌 test mapper'))
        self.assertIn('不在当前谱库', self.query.execute('单曲榜 999'))

    def test_invalid_snapshot_retained_as_unavailable(self):
        self.publish(server='wrong')
        self.assertIn('暂时不可用', self.query.execute('总榜'))
        self.publish(players=self.players + [self.players[0]])
        self.assertIn('暂时不可用', self.query.execute('总榜'))
        self.publish(updated=(self.now - 121) * 1000)
        self.assertIn('更新延迟', self.query.execute('总榜'))
        self.publish(updated=(self.now + 301) * 1000)
        self.assertIn('暂时不可用', self.query.execute('总榜'))
        self.write(self.root / 'status.json', {'schema': 2, 'revision': '../escape'})
        self.assertIn('暂时不可用', self.query.execute('总榜'))

    def test_gate_dedup_and_cooldown(self):
        clock = [100]
        gate = Gate(clock=lambda: clock[0])
        self.assertTrue(gate.accept('group', 'user', '1'))
        clock[0] += 4
        self.assertFalse(gate.accept('group', 'user', '1'))
        self.assertTrue(gate.accept('group', 'user', '2'))
        self.assertFalse(gate.accept('group', 'other', '3'))
        self.assertTrue(gate.accept('other-group', 'other', '4'))

    def test_hidden_creators_excluded_before_search_count_and_limit(self):
        names = ['阿峦', '夜語', 'ALWAYS_OVER', 'Kevin.X', '節奏大師譜面團隊',
                 'imddesigner', 'ＡＬＷＡＹＳＯＶＥＲ', '合作 / 藍貓', 'GM1YA']
        doc = {'server': 'fixture', 'charts': [dict(cid=i+1, title='Shared Song', creator=name)
                                              for i, name in enumerate(names)]}
        public = public_catalogue(doc)
        self.assertEqual([r['cid'] for r in public['charts'] if r['search_visible']], [9])
        self.write(self.root / 'catalog.json', public)
        self.assertEqual([r['cid'] for r in self.query.search('shared')], [9])
        self.assertIn('1 张谱面', self.query.execute('查歌 Shared'))
        self.assertIn('没有找到', self.query.execute('查歌 夜語'))
        self.assertNotIn('夜語', self.query.execute('单曲榜 Shared'))


if __name__ == '__main__':
    unittest.main()
