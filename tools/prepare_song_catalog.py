"""Read song metadata and optionally publish bounded thumbnails. Secrets stay in memory."""
import base64
import concurrent.futures
import getpass
import hashlib
import io
import json
import os
from pathlib import Path
import sqlite3
import sys
import time
import urllib.parse
import urllib.request
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
SERVER = 'http://47.111.184.207:5244'


def request(path, data=None, token='', method=None, headers=None, limit=16000000):
    h = {'Authorization': token, **(headers or {})}
    if isinstance(data, dict):
        data = json.dumps(data).encode()
        h['Content-Type'] = 'application/json'
    req = urllib.request.Request(SERVER + path, data, h, method=method)
    with urllib.request.urlopen(req, timeout=25) as response:
        b = response.read(limit + 1)
    if len(b) > limit:
        raise ValueError('Response exceeds size limit')
    return json.loads(b)


def login(user, password):
    r = request('/api/auth/login', {'username': user, 'password': password})
    if r.get('code') != 200:
        raise RuntimeError('Server login failed')
    return r['data']['token']


def main():
    config = json.loads((ROOT / '.secrets/default-reader.json').read_text(encoding='utf-8'))
    auth = base64.b64encode((config['username'] + ':' + config['password']).encode()).decode()
    with urllib.request.urlopen(urllib.request.Request(config['database_url'], headers={'Authorization': 'Basic ' + auth}), timeout=25) as r:
        data = r.read(16000001)
    assert data.startswith(b'SQLite format 3\0') and len(data) <= 16000000
    database = ROOT / '.cache/catalog-current.db'
    database.parent.mkdir(exist_ok=True)
    database.write_bytes(data)
    connection = sqlite3.connect(database.resolve().as_uri() + '?mode=ro', uri=True)
    connection.row_factory = sqlite3.Row
    rows = [dict(r) for r in connection.execute('''SELECT c.cid,s.sid,c.hash server_hash,c.path,c.mc_name,c.version,c.mode,c.level,s.title,s.artist,s.promote,s.cover,s.background,s.bpm,c.creator FROM charts c JOIN songs s ON c.sid=s.sid WHERE c.exist=1 AND s.exist=1''')]
    connection.close()
    snapshot = {'server': SERVER, 'updated_at': int(time.time()), 'charts': rows}
    (ROOT / 'android/assets/catalog.json').write_text(json.dumps(snapshot, ensure_ascii=False), encoding='utf-8')
    poster_path = ROOT / 'android/assets/poster-catalog.json'
    poster = json.loads(poster_path.read_text(encoding='utf-8'))
    poster['charts'] = rows
    poster_path.write_text(json.dumps(poster, ensure_ascii=False), encoding='utf-8')
    print(f'Metadata prepared: {len(rows)} charts with BPM and creator.', flush=True)
    if '--publish-art' not in sys.argv:
        return
    # Only the build-time source account can read chart artwork; it is NEVER bundled.
    password = os.environ.get('MALODY_ART_SOURCE_PASSWORD') or getpass.getpass('Artwork source password: ')
    source_token = login(os.environ.get('MALODY_ART_SOURCE_USER', 'GMIYA'), password)
    rank = json.loads((ROOT / '.secrets/default-rank.json').read_text(encoding='utf-8'))
    token = login(rank['username'], rank['password'])
    with urllib.request.urlopen(urllib.request.Request(SERVER + '/api/me', headers={'Authorization': token}), timeout=15) as r:
        assert json.load(r)['data']['base_path'].rstrip('/') == '/RMB30'
    assert request('/api/fs/mkdir', {'path': '/_song_art'}, token)['code'] == 200
    existing = set()
    for page in range(1, 100):
        r = request('/api/fs/list', {'path': '/_song_art', 'page': page, 'per_page': 100, 'refresh': True}, token)
        assert r['code'] == 200
        entries = r['data'].get('content') or []
        existing.update(e['name'] for e in entries if not e['is_dir'] and e.get('size', 0) > 0)
        if len(entries) < 100:
            break
    songs = {}
    for row in rows:
        songs.setdefault(row['sid'], row)
    cache = ROOT / '.cache/song-art'
    cache.mkdir(exist_ok=True)

    def publish(row):
        for raw_path in [row.get('cover'), row.get('background')]:
            if not raw_path:
                continue
            path = raw_path.replace('\\', '/').lstrip('/')
            if '..' in path.split('/'):
                continue
            digest = hashlib.sha256(path.encode()).hexdigest()
            name = digest + '.jpg'
            if name in existing:
                return 'present'
            try:
                local = cache / name
                if local.exists():
                    image_data = local.read_bytes()
                else:
                    r = request('/api/fs/get', {'path': '/' + path, 'password': ''}, source_token)
                    if r['code'] != 200:
                        continue
                    url = r['data']['raw_url']
                    origin = urllib.parse.urlsplit(url)
                    h = {'Authorization': source_token} if origin.netloc == urllib.parse.urlsplit(SERVER).netloc else {}
                    with urllib.request.urlopen(urllib.request.Request(url, headers=h), timeout=25) as response:
                        raw = response.read(8000001)
                    if len(raw) > 8000000:
                        continue
                    with Image.open(io.BytesIO(raw)) as image:
                        image = ImageOps.fit(ImageOps.exif_transpose(image).convert('RGB'), (384, 384))
                        out = io.BytesIO()
                        image.save(out, 'JPEG', quality=85, optimize=True)
                        image_data = out.getvalue()
                    local.write_bytes(image_data)
                r = request('/api/fs/put', image_data, token, method='PUT', headers={'File-Path': '/_song_art/' + name, 'As-Task': 'false', 'Content-Type': 'image/jpeg'})
                if r['code'] == 200:
                    return 'published'
            except Exception:
                continue
        return 'missing'

    counts = {'published': 0, 'present': 0, 'missing': 0}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        for i, result in enumerate(pool.map(publish, songs.values()), 1):
            counts[result] += 1
            if i % 100 == 0:
                print(f'Artwork {i}/{len(songs)}: {counts}', flush=True)
    print('Artwork complete: ' + json.dumps(counts), flush=True)


if __name__ == '__main__':
    main()
