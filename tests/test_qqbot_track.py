from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import urllib.error


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'server/qqbot'))
from music_recommendations import MusicRecommendations
from music_track import fetch_track, resolve_track_input


SHARE = r'LunaticSounds《Drop Down (Long.ver)》https\://c6.y.qq.com/base/fcgi-bin/u?\_\_=DemcZwOKqgeN @QQ音乐'
MID = '0010X6j20tpUC8'
DETAIL = f'https://y.qq.com/n/ryqq_v2/songDetail/{MID}'


class TrackTests(unittest.TestCase):
    def test_escaped_short_link_and_redirect_validation(self):
        response = urllib.error.HTTPError(
            '', 302, 'Found', {'Location': f'https://i.y.qq.com/v8/playsong.html?songmid={MID}'}, None)
        with patch('music_album.urllib.request.build_opener') as build:
            build.return_value.open.side_effect = response
            self.assertEqual(resolve_track_input(SHARE), ('songmid', MID))
            response.headers['Location'] = 'https://offsite.example/v8/playsong.html?songmid=' + MID
            with self.assertRaises(ValueError):
                resolve_track_input(SHARE)
            self.assertEqual(build.return_value.open.call_count, 2)
        with self.assertRaises(ValueError):
            resolve_track_input(DETAIL + ' https://y.qq.com/n/ryqq/songDetail/Other')

    def test_track_metadata_uses_canonical_mid(self):
        data = {'code': 0, 'data': [{'id': 252071171, 'mid': MID, 'title': 'Drop Down (Long.ver)',
                                    'singer': [{'name': 'LunaticSounds'}], 'album': {'mid': 'Album'}}]}
        with patch('music_track.json_get', return_value=data) as get:
            song = fetch_track('songid', '252071171')
            self.assertIn('songid=252071171', get.call_args.args[0])
            self.assertEqual(song, dict(mid=MID, title='Drop Down (Long.ver)', artist='LunaticSounds', album='Album'))
            with self.assertRaises(ValueError):
                fetch_track('songmid', 'Other')

    def test_single_track_joins_existing_rotation_and_deduplicates(self):
        base = ROOT / 'build/qqbot-tests'
        base.mkdir(parents=True, exist_ok=True)
        song = dict(mid=MID, title='Drop Down (Long.ver)', artist='LunaticSounds', album='')
        other = dict(song, mid='Other')
        with tempfile.TemporaryDirectory(dir=base) as temp:
            path = Path(temp) / 'music.db'
            binding = SimpleNamespace(get=lambda group, member: '12345' if member == 'bound' else None)
            with patch('music_recommendations.fetch_track', return_value=song):
                music = MusicRecommendations(path, binding, 'unused')
                self.assertIn('请先发送', music.execute('group1', 'unbound', '添加单曲 ' + DETAIL))
                self.assertIn('已添加', music.execute('group1', 'bound', '/添加单曲 ' + DETAIL))
                self.assertIn('已添加过', music.execute('group2', 'bound', '添加单曲 ' + DETAIL))
            music = MusicRecommendations(path, binding, 'unused', fetcher=lambda _: ('Likes', [song, other]))
            self.assertEqual(set(music.pool()), {MID})
            music.execute('group1', 'bound', '绑定歌单 1')
            self.assertEqual(set(music.pool()), {MID, 'Other'})
            first = music.pick('group2')['mid']
            music.delivered('group2', first)
            self.assertNotEqual(first, music.pick('group2')['mid'])
