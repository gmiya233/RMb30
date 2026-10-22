"""Read-only catalogue/history integration; only derived community files are published."""
import hashlib
import json
import math
from pathlib import Path
import re
import sqlite3
import time
import unicodedata
from contextlib import closing

TOPIC = re.compile(r'[sc][1-9][0-9]*')
QQ = re.compile(r'[1-9][0-9]{4,11}')
REVIEW = re.compile(r'review-([a-f0-9]{64})\.json')
BOARDS = ('total', 'song_hot', 'song_good', 'creator_hot', 'creator_good')
MAX_REVIEW_BYTES = 16384
DEFAULT_HIDDEN_CREATORS = ('阿峦', '夜语', 'alwaysover', '神马', 'noodle', '魂魄',
                           'liza', 'kevinx', 'kevin.x', '修罗', 'phizer', '破轴', 'printz',
                           'wolfr', 'wolf.r', 'ayya', 'promi', '临时工', '蓝猫', '节奏大师谱面团队')


def creator_visible(name, show_defaults=False):
    value = unicodedata.normalize('NFKC', name).casefold().translate(str.maketrans(
        {'馬': '马', '語': '语', '巒': '峦', '羅': '罗', '軸': '轴', '臨': '临',
         '時': '时', '藍': '蓝', '貓': '猫', '節': '节', '師': '师', '譜': '谱',
         '團': '团', '隊': '队'}))
    value = re.sub(r'[\s_-]+', '', value)
    if 'imddesigner' in value:
        return False
    return show_defaults or not any(alias in value for alias in DEFAULT_HIDDEN_CREATORS)


def read_json(path, limit=16 * 1024 * 1024):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > limit:
        raise ValueError('Invalid source file')
    with path.open('rb') as source:
        data = source.read(limit + 1)
    if len(data) > limit:
        raise ValueError('Source too large')
    return json.loads(data)


def atomic(path, doc):
    body = json.dumps(doc, ensure_ascii=False, separators=(',', ':'), allow_nan=False)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.tmp')
    temporary.write_text(body, encoding='utf-8')
    temporary.replace(path)


def topic_for(chart):
    return 's' + str(chart['sid']) if chart.get('sid', 0) > 0 else 'c' + str(chart['cid'])


def review_document(data, topic, qq, server):
    if not TOPIC.fullmatch(topic) or not QQ.fullmatch(qq):
        raise ValueError('Invalid review identity')
    if data.get('schema') != 1 or data.get('topic') != topic or data.get('qq') != qq or data.get('server') != server:
        raise ValueError('Review namespace mismatch')
    stars, text, name, nonce = (data.get(k) for k in ('stars', 'text', 'name', 'nonce'))
    if type(stars) is not int or not 1 <= stars <= 5:
        raise ValueError('Stars must be integers from 1 to 5')
    if not isinstance(text, str) or len(text.encode('utf-16-le')) > 1000 or not isinstance(name, str) or not 1 <= len(name.strip().encode('utf-16-le')) // 2 <= 128:
        raise ValueError('Invalid review text')
    if not isinstance(nonce, str) or not re.fullmatch(r'[a-f0-9-]{36}', nonce):
        raise ValueError('Invalid review nonce')
    return {'qq': qq, 'topic': topic, 'name': name.strip(), 'stars': stars,
            'text': text.strip(), 'nonce': nonce}


def catalogue(charts, downloads):
    songs, creators = {}, {}
    for chart in charts:
        topic = topic_for(chart)
        song = songs.setdefault(topic, {'id': topic, 'name': str(chart.get('title') or '未命名歌曲')[:512],
                                       'subtitle': str(chart.get('artist') or '')[:512], 'cid': chart['cid'],
                                       'downloads': 0, 'creators': set(), 'chart_downloads': {}})
        count = max(0, downloads.get(chart['cid'], 0))
        song['downloads'] += count
        song['chart_downloads'][str(chart['cid'])] = count
        name = str(chart.get('creator') or '').strip()
        if name:
            normalized = unicodedata.normalize('NFKC', name).casefold()
            key = hashlib.sha256(normalized.encode()).hexdigest()
            creator = creators.setdefault(key, {'id': key, 'name': name, 'downloads': 0, 'topics': set()})
            creator['downloads'] += count
            creator['topics'].add(topic)
            song['creators'].add(key)
    return songs, creators


def rank(rows, good=False):
    rows = sorted(rows, key=lambda row: (-row['average'], -row['rating_count'], -row['downloads'], row['id']) if good else (-row['downloads'], row['id']))
    previous = None
    for i, row in enumerate(rows):
        score = (row['average'], row['rating_count'], row['downloads']) if good else (row['downloads'],)
        row['rank'] = previous['rank'] if previous and score == previous['_tie'] else i + 1
        row['_tie'] = score
        previous = row
    for row in rows:
        row.pop('_tie', None)
    return rows[:50]


def aggregate(charts, downloads, reviews, totals):
    songs, creators = catalogue(charts, downloads)
    by_song = {topic: [] for topic in songs}
    for (topic, qq), review in reviews.items():
        if topic in by_song:
            by_song[topic].append(review)
    song_rows, creator_rows, song_docs = [], [], {}
    for topic, song in songs.items():
        entries = sorted(by_song[topic], key=lambda row: (-row['updated'], row['qq']))
        average = sum(row['stars'] for row in entries) / len(entries) if entries else 0
        song_rows.append({k: v for k, v in song.items() if k not in ('creators', 'chart_downloads')} | {'average': average, 'rating_count': len(entries)})
        song_docs[topic] = {'topic': topic, 'downloads': song['downloads'], 'average': average,
                            'chart_downloads': song['chart_downloads'], 'rating_count': len(entries),
                            'reviews': [{k: v for k, v in r.items() if not k.startswith('_')} for r in entries[:200]]}
    for creator in creators.values():
        entries = [review for topic in creator['topics'] for review in by_song[topic]]
        creator_rows.append({'id': creator['id'], 'name': creator['name'], 'cid': 0,
                             'subtitle': str(len(creator['topics'])) + ' 首作品',
                             'downloads': creator['downloads'], 'rating_count': len(entries),
                             'average': sum(r['stars'] for r in entries) / len(entries) if entries else 0})
    ordered = sorted((dict(p) for p in totals), key=lambda p: (-p['rating'], p['qq']))
    for i, player in enumerate(ordered):
        player['rank'] = ordered[i - 1]['rank'] if i and player['rating'] == ordered[i - 1]['rating'] else i + 1
    boards = {'total': ordered[:50], 'song_hot': rank([dict(s) for s in song_rows]),
              'song_good': rank([dict(s) for s in song_rows if s['rating_count']], True),
              'creator_hot': rank([dict(s) for s in creator_rows if creator_visible(s['name'])]),
              'creator_hot_all': rank([dict(s) for s in creator_rows if creator_visible(s['name'], True)]),
              'creator_good': rank([dict(s) for s in creator_rows if s['rating_count']], True)}
    return boards, song_docs


class Worker:
    def __init__(self, root, database, catalog, state):
        self.root, self.database, self.catalog, self.state = map(Path, (root, database, catalog, state))
        self.root = self.root.resolve(strict=True)
        self.state.mkdir(parents=True, exist_ok=True)
        if self.state.resolve().is_relative_to(self.root):
            raise ValueError('Private state must be outside archives')
        self.output = self.root / '_community/v1'
        self.cache, self.previous, self.song_hashes = {}, {}, {}
        saved = self.state / 'reviews.json'
        if saved.exists():
            for row in read_json(saved, 64 * 1024 * 1024)['reviews']:
                self.previous[row['topic'], row['qq']] = row
        self.signature, self.published = '', 0

    def reviews(self, server, topics):
        selected = {k: v for k, v in self.previous.items() if k[0] in topics}
        directory = self.output / 'reviews'
        failed, seen = 0, 0
        if directory.exists():
            if directory.is_symlink():
                raise ValueError('Invalid review directory')
            for topic_dir in sorted(directory.iterdir()):
                if topic_dir.name not in topics or not topic_dir.is_dir() or topic_dir.is_symlink():
                    continue
                for player_dir in sorted(topic_dir.iterdir()):
                    if not QQ.fullmatch(player_dir.name) or player_dir.is_symlink() or not player_dir.is_dir():
                        continue
                    for source in sorted(player_dir.iterdir()):
                        match = REVIEW.fullmatch(source.name)
                        if not match:
                            continue
                        seen += 1
                        if seen > 20000:
                            raise ValueError('Review archive capacity reached; prior snapshots retained')
                        try:
                            stat = source.stat()
                            signature = (stat.st_mtime_ns, stat.st_size)
                            cached = self.cache.get(str(source))
                            if cached and cached[0] == signature:
                                review = cached[1]
                            else:
                                if source.is_symlink() or not source.is_file() or stat.st_size > MAX_REVIEW_BYTES:
                                    raise ValueError('Invalid review source')
                                body = source.read_bytes()
                                if len(body) > MAX_REVIEW_BYTES or hashlib.sha256(body).hexdigest() != match.group(1):
                                    raise ValueError('Review checksum mismatch')
                                review = review_document(json.loads(body), topic_dir.name, player_dir.name, server)
                                if stat.st_mtime > time.time() + 300:
                                    raise ValueError('Invalid review time')
                                review.update(updated=int(stat.st_mtime * 1000), _order=[stat.st_mtime_ns, match.group(1)])
                                self.cache[str(source)] = signature, review
                            key = review['topic'], review['qq']
                            if key not in selected or review['_order'] > selected[key]['_order']:
                                selected[key] = review
                        except (ValueError, TypeError, KeyError, OSError):
                            failed += 1
        return selected, failed

    def refresh(self):
        catalog = read_json(self.catalog)
        server, charts = catalog['server'], catalog['charts']
        status = read_json(self.root / '_rankings/v2/status.json')
        revision = status['revision']
        if not re.fullmatch(r'[a-f0-9]{64}', revision):
            raise ValueError('Invalid ranking revision')
        totals = read_json(self.root / '_rankings/v2' / revision / 'rt.json')
        if totals['server'] != server or totals['revision'] != revision:
            raise ValueError('Ranking namespace mismatch')
        with closing(sqlite3.connect(self.database.resolve().as_uri() + '?mode=ro', uri=True)) as db:
            downloads = {int(cid): max(0, int(count or 0)) for cid, count in db.execute('SELECT cid,download_count FROM stats')}
        reviews, failed = self.reviews(server, {topic_for(c) for c in charts})
        boards, songs = aggregate(charts, downloads, reviews, totals['players'])
        now = int(time.time() * 1000)
        header = {'schema': 1, 'server': server, 'revision': revision}
        doc = header | {'boards': boards, 'failed': failed, 'songs': len(songs)}
        signature = hashlib.sha256(json.dumps(doc, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        for topic, song in songs.items():
            data = header | song
            stamp = hashlib.sha256(json.dumps(data, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
            if self.song_hashes.get(topic) != stamp:
                atomic(self.output / 'songs' / (topic + '.json'), data | {'updated': now})
                self.song_hashes[topic] = stamp
        if signature != self.signature or now - self.published > 30000:
            atomic(self.output / 'boards.json', doc | {'updated': now})
            self.signature, self.published = signature, now
        if reviews != self.previous:
            atomic(self.state / 'reviews.json', {'reviews': list(reviews.values())})
            self.previous = reviews
        return {'songs': len(songs), 'reviews': len(reviews), 'failed': failed}


def main():
    import sys
    config = read_json(Path('/etc/malody-ranking-paths.json'))
    worker = Worker(config['root'], config['database'], '/var/lib/malody-ranking/catalog.json', '/var/lib/malody-ranking/community')
    if '--once' in sys.argv:
        print(json.dumps(worker.refresh()))
        return
    last = None
    while True:
        try:
            status = worker.refresh()
            if status != last:
                print(json.dumps(status), flush=True)
                last = status
        except Exception as failure:
            print('Community refresh failed; snapshots retained (' + type(failure).__name__ + ')', flush=True)
        time.sleep(5)


if __name__ == '__main__':
    main()
