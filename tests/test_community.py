"""Community integration fixtures; never connects to production or a device."""
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import time
import unittest
import uuid
from contextlib import closing

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
from community import Worker, aggregate, atomic, review_document, creator_visible, DEFAULT_HIDDEN_CREATORS

BUILD = Path(__file__).resolve().parents[1] / 'build'
SERVER = 'https://fixture.invalid'
REVISION = 'a' * 64


def chart(cid, sid, creator):
    return {'cid': cid, 'sid': sid, 'title': 'Song ' + str(sid), 'artist': 'Fixture', 'creator': creator}


def review(topic='s1', qq='12345', stars=5, text='Synthetic fixture'):
    return {'schema': 1, 'server': SERVER, 'topic': topic, 'qq': qq, 'name': 'Fixture',
            'stars': stars, 'text': text, 'nonce': str(uuid.uuid4())}


class CommunityTest(unittest.TestCase):
    def setUp(self):
        BUILD.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=BUILD, prefix='community-fixture-')
        self.base = Path(self.temp.name)
        self.root = self.base / 'archives'
        self.root.mkdir()
        self.charts = [chart(1, 1, 'Mapper A'), chart(2, 1, 'Mapper A'), chart(3, 2, 'Mapper B'), chart(4, 3, 'Mapper A')]
        self.downloads = {1: 100, 2: 50, 3: 80, 4: 40}
        self.database = self.base / 'catalog.db'
        with closing(sqlite3.connect(self.database)) as db:
            db.execute('CREATE TABLE stats(cid INTEGER, download_count INTEGER)')
            db.executemany('INSERT INTO stats VALUES (?,?)', self.downloads.items())
            db.commit()
        self.database_before = self.database.read_bytes()
        self.catalog = self.base / 'catalog.json'
        atomic(self.catalog, {'server': SERVER, 'charts': self.charts})
        atomic(self.root / '_rankings/v2/status.json', {'revision': REVISION})
        self.players = [{'qq': '12345', 'name': 'Fixture', 'rating': 12, 'o': 12, 'n': 12, 'oc': 20, 'nc': 10, 'best': []}]
        self.rt = self.root / '_rankings/v2' / REVISION / 'rt.json'
        atomic(self.rt, {'server': SERVER, 'revision': REVISION, 'players': self.players})
        self.rt_before = self.rt.read_bytes()
        self.state = self.base / 'private-state'
        self.worker = Worker(self.root, self.database, self.catalog, self.state)
        self.tick = time.time_ns() - 1000000000

    def tearDown(self):
        self.temp.cleanup()

    def post(self, doc):
        body = json.dumps(doc, ensure_ascii=False).encode()
        folder = self.root / '_community/v1/reviews' / doc['topic'] / doc['qq']
        folder.mkdir(parents=True, exist_ok=True)
        target = folder / ('review-' + hashlib.sha256(body).hexdigest() + '.json')
        target.write_bytes(body)
        self.tick += 1000000
        os.utime(target, ns=(self.tick, self.tick))
        return target

    def test_download_aggregation_and_top50(self):
        boards, songs = aggregate(self.charts, self.downloads, {}, self.players)
        self.assertEqual(songs['s1']['chart_downloads'], {'1': 100, '2': 50})
        self.assertEqual(songs['s2']['chart_downloads'], {'3': 80})
        self.assertNotIn('chart_downloads', boards['song_hot'][0])
        self.assertEqual([(r['id'], r['downloads']) for r in boards['song_hot']], [('s1', 150), ('s2', 80), ('s3', 40)])
        self.assertEqual([(r['name'], r['downloads']) for r in boards['creator_hot']], [('Mapper A', 190), ('Mapper B', 80)])
        self.assertFalse(boards['song_good'])
        self.assertEqual(boards['total'][0]['rating'], self.players[0]['rating'])
        self.assertNotIn('rank', self.players[0])
        charts = [chart(i, i, 'Mapper') for i in range(1, 81)]
        boards, _ = aggregate(charts, {i: i for i in range(1, 81)}, {}, [])
        self.assertEqual(len(boards['song_hot']), 50)
        self.assertEqual(boards['song_hot'][0]['downloads'], 80)
        self.assertEqual(boards['song_hot'][-1]['downloads'], 31)

    def test_creator_visibility_and_filter_before_top50(self):
        for name in ('imddesigner', 'IMD_Designer', 'ＩＭＤＤｅｓｉｇｎｅｒ', 'imddesigner / Mapper'):
            self.assertFalse(creator_visible(name))
            self.assertFalse(creator_visible(name, True))
        for name in (*DEFAULT_HIDDEN_CREATORS, 'Asura夜语', '神馬 a.k.a. Mapper', 'wolfR', 'WOLF.R', 'AYYA', 'PROMI', 'Kevin.x', 'Ｋｅｖｉｎ．ｘ', '節奏大師譜面團隊'):
            self.assertFalse(creator_visible(name), name)
            self.assertTrue(creator_visible(name, True), name)
        self.assertTrue(creator_visible('Independent Mapper'))
        charts = [chart(i, i, 'Mapper ' + str(i)) for i in range(1, 61)]
        charts += [chart(100, 100, 'imddesigner'), chart(101, 101, '阿峦')]
        downloads = {c['cid']: c['cid'] for c in charts}
        votes = {('s100', '12345'): review(topic='s100') | {'updated': 1},
                 ('s101', '12345'): review(topic='s101') | {'updated': 1}}
        boards, _ = aggregate(charts, downloads, votes, [])
        self.assertEqual(len(boards['creator_hot']), 50)
        self.assertEqual(boards['creator_hot'][0]['name'], 'Mapper 60')
        self.assertEqual(boards['creator_hot'][-1]['name'], 'Mapper 11')
        self.assertEqual(boards['creator_hot_all'][0]['name'], '阿峦')
        self.assertEqual(len(boards['creator_hot_all']), 50)
        self.assertNotIn('imddesigner', [r['name'] for r in boards['creator_hot_all']])
        self.assertEqual({r['name'] for r in boards['creator_good']}, {'imddesigner', '阿峦'})

    def test_rating_edit_one_vote_and_creator_dedup(self):
        old = self.post(review())
        self.post(review(qq='23456', stars=1))
        self.post(review(topic='s2', stars=4))
        self.worker.refresh()
        song = json.loads((self.root / '_community/v1/songs/s1.json').read_text(encoding="utf-8"))
        self.assertEqual((song['average'], song['rating_count']), (3, 2))
        board = json.loads((self.root / '_community/v1/boards.json').read_text(encoding="utf-8"))['boards']
        self.assertEqual(board['song_good'][0]['id'], 's2')
        mapper = next(r for r in board['creator_good'] if r['name'] == 'Mapper A')
        self.assertEqual((mapper['average'], mapper['rating_count']), (3, 2))
        edited = review(stars=2, text='Edited synthetic fixture')
        self.post(edited)
        self.worker.refresh()
        song = json.loads((self.root / '_community/v1/songs/s1.json').read_text(encoding="utf-8"))
        self.assertEqual((song['average'], song['rating_count']), (1.5, 2))
        self.assertEqual(song['reviews'][0]['nonce'], edited['nonce'])
        self.assertTrue(old.exists(), 'original reviews remain recoverable')
        self.assertEqual(self.database.read_bytes(), self.database_before)
        self.assertEqual(self.rt.read_bytes(), self.rt_before)

    def test_corrupt_input_retains_good_review_across_restart(self):
        path = self.post(review(stars=4))
        self.worker.refresh()
        path.write_bytes(b'broken')
        self.worker = Worker(self.root, self.database, self.catalog, self.state)
        result = self.worker.refresh()
        self.assertEqual(result['failed'], 1)
        song = json.loads((self.root / '_community/v1/songs/s1.json').read_text(encoding="utf-8"))
        self.assertEqual((song['average'], song['rating_count']), (4, 1))

    def test_validation_and_timestamp_authority(self):
        for stars in (0, 6, 1.5, True):
            with self.assertRaises(ValueError):
                review_document(review(stars=stars), 's1', '12345', SERVER)
        review_document(review(text='😀' * 250), 's1', '12345', SERVER)
        for text in ('x' * 501, '😀' * 251, None):
            with self.assertRaises(ValueError):
                review_document(review(text=text), 's1', '12345', SERVER)
        with self.assertRaises(ValueError):
            review_document(review(), '../s1', '12345', SERVER)
        with self.assertRaises(ValueError):
            review_document(review(), 's2', '12345', SERVER)
        first = review(stars=5)
        first['updated'] = 999999999999999
        self.post(first)
        self.post(review(stars=2))
        self.worker.refresh()
        song = json.loads((self.root / '_community/v1/songs/s1.json').read_text(encoding="utf-8"))
        self.assertEqual(song['average'], 2, 'client-provided clock cannot pin an old rating')


if __name__ == '__main__':
    unittest.main()
