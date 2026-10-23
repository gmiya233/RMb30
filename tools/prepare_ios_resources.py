"""Reuse the published Android client's scoped configuration. Never log credentials."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=Path, required=True)
    args = parser.parse_args()
    target = ROOT / 'ios/Resources'
    target.mkdir(parents=True, exist_ok=True)
    names = {'catalog.json': 'catalog.json', 'default-rank.json': 'runtime-config.json', 'default-reader.json': 'reader-config.json'}
    with zipfile.ZipFile(args.apk) as archive:
        for source, output in names.items():
            info = archive.getinfo('assets/' + source)
            if info.file_size > 16_000_000:
                raise ValueError('Bundled resource exceeds limit')
            data = archive.read(info)
            doc = json.loads(data)
            if source == 'catalog.json':
                assert doc.get('charts') and len(doc['charts']) <= 50_000
            elif source == 'default-rank.json':
                assert doc['root'] == '/RMB30'
            else:
                assert doc['database_url'].endswith('/dav/malody.db')
            (target / output).write_bytes(data)
    (target / 'build-info.json').write_text(json.dumps({'ios_version': '0.1.0', 'android_source_sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest()}), encoding='utf-8')
    print('Prepared bounded iOS catalogue and scoped app configuration; no player archives copied.')


if __name__ == '__main__':
    main()
