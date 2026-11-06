"""Publish a current-chart to content-addressed artwork map for mobile clients."""
import hashlib
import json
import os
from pathlib import Path
from b30_data import artwork_candidates


def publish(catalogue, target, write, root=None):
    if root is None: root = json.loads(Path('/etc/malody-ranking-paths.json').read_text())['root']
    out = (Path(os.environ['MALODY_PUBLIC_ART_ROOT']) if os.environ.get('MALODY_PUBLIC_ART_ROOT') else Path(root) / '_song_art') / 'v2'
    roots = [Path(target).parent / 'thumbs', Path('/var/lib/malody-qqbot/artwork'),
             Path('/var/lib/malody-qqbot/catalog-source/thumbs'), out.parent]
    rows = {}
    for chart in catalogue['charts']:
        for digest in artwork_candidates(chart):
            found = False
            for root in roots:
                source = root / (digest + '.jpg')
                if source.is_symlink() or not source.is_file() or not 0 < source.stat().st_size <= 1048576:
                    continue
                data = source.read_bytes()
                content = hashlib.sha256(data).hexdigest()
                dest = out / (content + '.jpg')
                if not dest.exists(): write(dest, data)
                rows[str(chart['cid'])] = {'server_hash': chart['server_hash'], 'image': content}
                found = True
                break
            if found: break
    doc = {'schema': 2, 'server': catalogue['server'], 'charts': rows}
    data = json.dumps(doc, ensure_ascii=False, separators=(',', ':')).encode()
    dest = out / 'index.json'
    if not dest.exists() or dest.read_bytes() != data: write(dest, data)

