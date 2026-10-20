"""Verify local charts against the server and send only allowlisted replays to the app.

Credentials are prompted (or supplied by temporary process environment variables).
No credentials are persisted. All device-source operations are read-only.
"""
import argparse
import getpass
import hashlib
import json
import os
import shlex
import sqlite3
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools/python-libs'))
from unqlite import UnQLite
from extract_score_snapshot import fields

ADB = Path('D:/platform-tools/adb.exe')
PACKAGE = 'cn.gmiya.malodylink'
MALODY = '/sdcard/Android/data/me.mugzone.emiria/files'


class Server:
    def __init__(self, base, username, password):
        self.base = base.rstrip('/')
        self.token = ''
        r = self.post('/api/auth/login', {'username': username, 'password': password})
        if r.get('code') != 200:
            raise RuntimeError('Server login failed')
        self.token = r['data']['token']

    def post(self, path, value):
        request = urllib.request.Request(self.base + path, json.dumps(value).encode(),
            {'Content-Type': 'application/json', 'Authorization': self.token})
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)

    def file(self, path):
        result = self.post('/api/fs/get', {'path': path, 'password': ''})
        if result.get('code') != 200:
            raise RuntimeError('Cannot read server file: ' + path)
        url = result['data']['raw_url']
        a, b = urllib.parse.urlsplit(self.base), urllib.parse.urlsplit(url)
        headers = {'Authorization': self.token} if (a.scheme, a.netloc) == (b.scheme, b.netloc) else {}
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as response:
            data = response.read(16000001)
        if len(data) > 16000000:
            raise RuntimeError('Server file exceeds size limit')
        return data


def gameplay(data):
    chart = json.loads(data)
    if not isinstance(chart.get('note'), list) or not isinstance(chart.get('time'), list):
        raise ValueError('Missing note/time arrays')
    result = {k: v for k, v in chart.items() if k not in ('meta', 'extra')}
    result['mode'] = chart['meta'].get('mode')
    result['mode_ext'] = chart['meta'].get('mode_ext', {})
    return result


def catalog(db_path, base):
    c = sqlite3.connect(db_path.resolve().as_uri() + '?mode=ro', uri=True)
    c.row_factory = sqlite3.Row
    try:
        rows = [dict(r) for r in c.execute('''SELECT c.cid,c.hash AS server_hash,c.path,c.mc_name,
            c.version,c.mode,s.title,s.artist,s.sid,c.level,s.promote,s.cover,s.background FROM charts c JOIN songs s ON c.sid=s.sid
            WHERE c.exist=1 AND s.exist=1''')]
    finally:
        c.close()
    return {'server': base.rstrip('/'), 'updated_at': int(time.time()), 'charts': rows}


def adb(serial, *args, data=None):
    p = subprocess.run([str(ADB), '-s', serial, *args], input=data,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
    return p.stdout


def device_file(serial, path):
    return adb(serial, 'exec-out', 'cat ' + shlex.quote(path))


def write_app(serial, name, data):
    if not all(c.isalnum() or c in '._-' for c in name):
        raise ValueError('Invalid inbox name')
    # exec-in is binary-safe. Older Windows adb shell stdin stops at byte 0x1a.
    adb(serial, 'exec-in', f"run-as {PACKAGE} sh -c 'cat > files/inbox/{name}'", data=data)
    received = adb(serial, 'exec-out', f'run-as {PACKAGE} cat files/inbox/{name}')
    if received != data:
        raise RuntimeError('USB transfer verification failed: ' + name)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--prepare-only', action='store_true', help='Prepare APK catalogue assets using local server snapshot')
    p.add_argument('--serial', default='10CF8T25GC001GX')
    p.add_argument('--server', default='http://47.111.184.207:5244')
    p.add_argument('--username', default='GMIYA')
    args = p.parse_args()
    private = ROOT / 'device-inspection/usb-sync'
    private.mkdir(parents=True, exist_ok=True)
    db = ROOT / '.server-inspection/malody.db'
    if args.prepare_only:
        assets = ROOT / 'android/assets'
        assets.mkdir(parents=True, exist_ok=True)
        data = catalog(db, args.server)
        # Baseline catalogue timestamp reflects when the snapshot was downloaded.
        data['updated_at'] = int(db.stat().st_mtime)
        (assets / 'catalog.json').write_text(json.dumps(data, ensure_ascii=False), encoding='utf-8')
        print('Prepared', len(data['charts']), 'active charts; no player scores bundled')
        return
    password = os.environ.get('MALODY_TEST_PASSWORD') or getpass.getpass('OpenList password (not saved): ')
    server = Server(args.server, args.username, password)
    password = None
    db = private / 'catalog.db'
    db.write_bytes(server.file('/data/malody.db'))
    data = catalog(db, args.server)
    by_name = {}
    for entry in data['charts']:
        by_name.setdefault(entry['mc_name'], []).append(entry)
    local_db = private / 'chart.bin'
    local_db.write_bytes(device_file(args.serial, MALODY + '/chart.bin'))
    aliases = {}
    chart_db = UnQLite(str(local_db), flags=1)
    try:
        with chart_db.cursor() as cursor:
            local_entries = list(cursor)
    finally:
        chart_db.close()
    checked = 0
    for _, raw in local_entries:
        parsed = {n: v for n, _, v in fields(raw)}
        path = parsed.get(7, b'').decode('utf-8')
        if not path.endswith('.mc') or '/Android/data/me.mugzone.emiria/files/chart/' not in path:
            continue
        candidates = by_name.get(path.rsplit('/', 1)[-1], [])
        if not candidates:
            continue
        local = device_file(args.serial, path)
        try:
            local_gameplay = gameplay(local)
        except (ValueError, KeyError, TypeError):
            print('Skipped unsupported local chart format:', path.rsplit('/', 1)[-1])
            continue
        matches = []
        for entry in candidates:
            try:
                remote = server.file('/' + entry['path'] + '/' + entry['mc_name'])
                if local_gameplay == gameplay(remote):
                    matches.append(entry)
            except (ValueError, KeyError, TypeError):
                print('Skipped unsupported server chart format CID', entry['cid'])
        if len(matches) != 1:
            continue
        h = hashlib.md5(local).hexdigest()
        aliases[h] = dict(matches[0], local_md5=h, verification='gameplay_equal')
        checked += 1
        print('Verified chart CID', matches[0]['cid'])
    manifest = {'catalog': data, 'aliases': aliases}
    (private / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False), encoding='utf-8')
    replay_paths = adb(args.serial, 'exec-out', 'find ' + shlex.quote(MALODY + '/replay') + " -type f -name '*.mrv'").decode('utf-8').splitlines()
    accepted = []
    accepted_charts = {}
    for path in replay_paths:
        raw = device_file(args.serial, path)
        # Check chart identity before interpreting any score data.
        h = next((v.decode('utf-8') for n, w, v in fields(raw) if n == 5 and w == 2), '')
        if h in aliases:
            accepted.append(raw)
            accepted_charts[aliases[h]['cid']] = aliases[h]
    # Prevent the UI from consuming/deleting a batch while it is being verified.
    adb(args.serial, 'shell', 'am force-stop ' + PACKAGE)
    adb(args.serial, 'shell', f'run-as {PACKAGE} mkdir -p files/inbox')
    for i, raw in enumerate(accepted):
        write_app(args.serial, f'replay_{i}.mrv', raw)
    for cid, entry in accepted_charts.items():
        cover = entry.get('cover') or entry.get('background')
        if cover:
            try:
                write_app(args.serial, f'jacket_{cid}.img', server.file('/' + cover.lstrip('/')))
            except Exception as exc:
                print('Cover unavailable for CID', cid, type(exc).__name__)
    # Manifest is written last so the app never sees a half-prepared batch.
    write_app(args.serial, 'manifest.json', json.dumps(manifest, ensure_ascii=False).encode('utf-8'))
    adb(args.serial, 'shell', 'am start -n ' + PACKAGE + '/.MainActivity')
    print('USB sync ready:', checked, 'verified charts;', len(accepted), 'matching replays;', len(replay_paths)-len(accepted), 'skipped')


if __name__ == '__main__':
    main()
