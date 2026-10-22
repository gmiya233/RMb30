"""Fetch descriptive key metadata through the existing scoped app account."""
import json
import os
from pathlib import Path
import sys
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LIMIT = 16000000


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def main():
    config = json.loads(Path(os.environ.get('MALODY_RANK_CONFIG',
                                           ROOT / '.secrets/default-rank.json')).read_text(encoding='utf-8'))
    base = config['server'].rstrip('/')
    origin = urllib.parse.urlsplit(base)
    if origin.scheme not in ('http', 'https') or origin.username or origin.password:
        raise ValueError('Invalid origin')
    opener = urllib.request.build_opener(NoRedirect())

    def request(url, data=None, token=''):
        headers = {'Authorization': token} if token else {}
        if data is not None:
            data = json.dumps(data).encode()
            headers['Content-Type'] = 'application/json'
        with opener.open(urllib.request.Request(url, data, headers), timeout=25) as response:
            body = response.read(LIMIT + 1)
        if len(body) > LIMIT:
            raise ValueError('Metadata exceeds capacity')
        return json.loads(body)

    def api(path, body, token=''):
        response = request(base + path, body, token)
        if response.get('code') != 200:
            raise ValueError('Metadata request failed')
        return response['data']

    token = api('/api/auth/login', {'username': config['username'],
                                    'password': config['password']})['token']
    me = request(base + '/api/me', token=token)
    if me.get('code') != 200 or me['data'].get('base_path', '').rstrip('/') != config['root']:
        raise ValueError('Unexpected account scope')
    raw = api('/api/fs/get', {'path': '/_catalog/keys-v1.json', 'password': ''}, token)['raw_url']
    target = urllib.parse.urlsplit(raw)
    if target.scheme not in ('http', 'https') or target.username or target.password:
        raise ValueError('Invalid metadata address')
    same = (target.scheme, target.hostname, target.port) == (origin.scheme, origin.hostname, origin.port)
    metadata = request(raw, token=token if same else '')
    if metadata.get('schema') != 1 or metadata.get('server') != base or len(metadata['charts']) > 100000:
        raise ValueError('Metadata namespace mismatch')
    ids = set()
    for chart in metadata['charts']:
        if chart['cid'] in ids or not isinstance(chart['keys'], int) or not 0 <= chart['keys'] <= 64:
            raise ValueError('Invalid key metadata')
        ids.add(chart['cid'])
    path = ROOT / 'android/assets/chart-keys.json'
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(metadata, ensure_ascii=False), encoding='utf-8')
    os.replace(temporary, path)
    print('Prepared key metadata:', len(metadata['charts']), 'charts')


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        print('Key metadata preparation failed:', type(failure).__name__, file=sys.stderr)
        sys.exit(1)
