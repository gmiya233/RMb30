"""Live sync test using ONLY a random, pre-checked absent synthetic QQ folder."""
import json
import subprocess
import sys
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
config = ROOT / '.secrets/default-rank.json'
settings = json.loads(config.read_text(encoding='utf-8'))
token = ''


def api(path, data):
    req = urllib.request.Request(settings['server'] + path, json.dumps(data).encode(),
        {'Authorization': token, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.load(response)


token = api('/api/auth/login', {'username': settings['username'], 'password': settings['password']})['data']['token']
with urllib.request.urlopen(urllib.request.Request(settings['server'] + '/api/me', headers={'Authorization': token}), timeout=15) as response:
    assert json.load(response)['data']['base_path'].rstrip('/') == '/RMB30'
qq = None
for _ in range(8):
    candidate = '999' + f'{uuid.uuid4().int % 1000000000:09d}'
    before = api('/api/fs/list', {'path': '/' + candidate, 'page': 1, 'per_page': 1, 'refresh': True})
    if before['code'] != 200 and 'object not found' in before.get('message', '').lower():
        qq = candidate
        break
if not qq:
    raise RuntimeError('Could not reserve an unused synthetic test folder')
try:
    if '--device' in sys.argv:
        result = subprocess.run(['D:/platform-tools/adb.exe', '-s', '10CF8T25GC001GX', 'shell', 'am', 'instrument', '-w',
            '-e', 'test_qq', qq, 'cn.gmiya.malodylink/.CloudProfileTestInstrumentation'], cwd=ROOT, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=90)
        print(result.stdout)
        if result.returncode or 'PASS: isolated QQ' not in result.stdout:
            raise RuntimeError('Device cloud test did not pass')
    else:
        subprocess.run(['java', '-cp', 'build/cloud-tests;tools/test-libs/json.jar', 'CloudRemoteIntegrationTest', str(config), qq], cwd=ROOT, check=True)
finally:
    # candidate was proven absent before this test; never delete an existing player's folder.
    state = api('/api/fs/list', {'path': '/' + qq, 'page': 1, 'per_page': 100, 'refresh': True})
    if state['code'] == 200:
        entries = state['data'].get('content') or []
        if any(e.get('is_dir') or not e.get('name', '').startswith('scores-') for e in entries):
            raise RuntimeError('Unexpected content in test folder; cleanup deliberately stopped')
        removed = api('/api/fs/remove', {'dir': '/', 'names': [qq]})
        if removed['code'] != 200:
            raise RuntimeError('Synthetic test folder cleanup failed')
        print('Cleaned only the synthetic folder created by this test.')
