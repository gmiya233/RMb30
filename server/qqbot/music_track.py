"""Resolve public QQ Music single-track shares."""
import re
import urllib.parse

from music_album import resolve_short, share_text, short_code
from music_preview import json_get


TRACK_KEY = re.compile(r'[A-Za-z0-9]{1,32}')
NUMERIC_ID = re.compile(r'[0-9]{1,15}')


def track_reference(value):
    url = urllib.parse.urlsplit(value)
    if (url.scheme != 'https' or url.hostname not in ('y.qq.com', 'i.y.qq.com', 'i2.y.qq.com')
            or url.username or url.password or url.port not in (None, 443)):
        raise ValueError('请发送QQ音乐官方歌曲分享链接。')

    if url.hostname == 'y.qq.com':
        match = re.fullmatch(r'/n/ryqq(?:_v2)?/songDetail/([A-Za-z0-9]{1,32})/?', url.path)
        if match:
            return 'songmid', match[1]
    if url.path in ('/v8/playsong.html', '/n3/other/pages/details/song.html', '/n2/m/share/details/song.html'):
        params = urllib.parse.parse_qs(url.query, keep_blank_values=True)
        for name, key, pattern in (('songmid', 'songmid', TRACK_KEY), ('songMid', 'songmid', TRACK_KEY),
                                   ('songid', 'songid', NUMERIC_ID), ('songId', 'songid', NUMERIC_ID)):
            if name in params:
                ids = params[name]
                if len(ids) == 1 and pattern.fullmatch(ids[0]):
                    return key, ids[0]
                raise ValueError('QQ音乐链接中的歌曲编号无效。')
    raise ValueError('请发送QQ音乐歌曲链接。')


def resolve_track_input(value):
    urls = re.findall(r'https://[^\s<>"\x27]+', share_text(value))
    if len(urls) > 4:
        raise ValueError('请一次添加一首歌。')

    refs = set()
    for url in urls:
        url = url.rstrip('，。；;）)]}》')
        try:
            refs.add(track_reference(url))
        except ValueError:
            try:
                refs.add(('short', short_code(url)))
            except ValueError:
                continue
    if len(refs) > 1:
        raise ValueError('请一次添加一首歌。')
    if not refs:
        raise ValueError('没有找到QQ音乐歌曲链接，请粘贴完整的单曲分享文本。')

    key, value = refs.pop()
    if key == 'short':
        return resolve_short('https://c.y.qq.com/base/fcgi-bin/u?__=' + value, track_reference)
    return key, value


def fetch_track(key, value):
    params = {'tpl': 'yqq_song_detail', 'format': 'json', key: value}
    url = 'https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg?' + urllib.parse.urlencode(params)
    detail = json_get(url)
    rows = detail.get('data') if isinstance(detail, dict) else None
    if not isinstance(rows, list) or len(rows) != 1 or not isinstance(rows[0], dict):
        raise ValueError('QQ音乐暂时无法读取这首歌。')
    if detail.get('code') != 0:
        raise ValueError('QQ音乐暂时无法读取这首歌。')
    row = rows[0]
    mid = row.get('mid', '')
    identity = mid if key == 'songmid' else str(row.get('id'))
    title = row.get('title') or row.get('name')
    if identity != value or not isinstance(mid, str) or not TRACK_KEY.fullmatch(mid) or not isinstance(title, str) or not title.strip():
        raise ValueError('QQ音乐返回的歌曲信息不完整。')
    album_info = row.get('album')
    album = album_info.get('mid', '') if isinstance(album_info, dict) else ''
    singers = row.get('singer')
    artist = ' / '.join(singer['name'] for singer in singers
                        if isinstance(singer, dict) and isinstance(singer.get('name'), str)) if isinstance(singers, list) else ''
    return dict(mid=mid, title=title[:200], artist=artist[:200],
                album=album if isinstance(album, str) and TRACK_KEY.fullmatch(album) else '')
