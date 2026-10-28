"""Publish immutable preview PNGs and small CID manifests into Rank1's scope."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import time

from render import VERSION, SKIN, MAX_BYTES, parse, render


def atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    stage = path.with_suffix(path.suffix + '.tmp')
    with stage.open('wb') as stream:
        stream.write(data); stream.flush(); os.fsync(stream.fileno())
    stage.chmod(0o644); stage.replace(path)


def source_file(root, row):
    directory, filename = str(row['path']).replace('\\', '/'), str(row['mc_name'])
    relative = PurePosixPath(directory) / filename
    if relative.is_absolute() or '..' in relative.parts or PurePosixPath(filename).name != filename or ':' in relative.as_posix():
        raise ValueError('Invalid chart path')
    source = root.joinpath(*relative.parts)
    if source.is_symlink() or not source.resolve().is_relative_to(root.resolve()) or source.stat().st_size > MAX_BYTES:
        raise ValueError('Invalid chart source')
    return source


def skin_revision():
    digest = hashlib.sha256(Path(__file__).with_name('render.py').read_bytes())
    for image in sorted(SKIN.glob('*.png')):
        digest.update(image.name.encode()); digest.update(image.read_bytes())
    return digest.hexdigest()


def publish(catalogue, source, output, state, font_path):
    state.mkdir(parents=True, exist_ok=True)
    try:
        stamps = json.loads((state / 'stamps.json').read_bytes())
    except (OSError, ValueError):
        stamps = {}
    skin = skin_revision()
    active = {}; done = failed = unchanged = 0
    for row in sorted(catalogue['charts'], key=lambda c: (not c.get('promote'), c['cid'])):
        cid = row['cid']
        if type(cid) is not int or cid <= 0:
            continue
        manifest = output / f'{cid}.json'
        base = {'schema': 1, 'server': catalogue['server'], 'cid': cid, 'server_hash': row['server_hash'], 'skin': 'EX Rhythm Master VI', 'renderer': VERSION}
        try:
            file = source_file(source, row); stat = file.stat()
            signature = hashlib.sha256(json.dumps([skin, stat.st_size, stat.st_mtime_ns, row], sort_keys=True, ensure_ascii=False).encode()).hexdigest()
            active[str(cid)] = signature
            if stamps.get(str(cid)) == signature and manifest.exists():
                try:
                    doc = json.loads(manifest.read_bytes())
                    if doc.get('status') == 'ready' and all((output / p['image']).is_file() for p in doc['pages']):
                        unchanged += 1; continue
                except (OSError, ValueError, KeyError, TypeError):
                    pass
            data = file.read_bytes()
            if len(data) > MAX_BYTES or (stat.st_size, stat.st_mtime_ns) != (file.stat().st_size, file.stat().st_mtime_ns):
                raise ValueError('Chart changed during read')
            chart = parse(data, row.get('keys'))
            pages = []
            for page in range(1, chart.pages + 1):
                image, (width, height) = render(chart, row, font_path, page)
                digest = hashlib.sha256(image).hexdigest()
                name = f'{cid}-{digest}.png'
                if not (output / name).exists():
                    atomic(output / name, image)
                pages.append({'image': name, 'sha256': digest, 'bytes': len(image), 'width': width, 'height': height})
            doc = base | {'status': 'ready', 'keys': chart.keys, 'notes': len(chart.notes), 'pages': pages,
                          'source_sha256': hashlib.sha256(data).hexdigest(), 'updated': int(time.time() * 1000)}
            atomic(manifest, json.dumps(doc, ensure_ascii=False, separators=(',', ':')).encode()); done += 1
        except (OSError, ValueError, KeyError, TypeError, OverflowError):
            # Never leave an old, now-incompatible chart image advertised as current.
            atomic(manifest, json.dumps(base | {'status': 'unavailable', 'pages': []}, ensure_ascii=False).encode()); failed += 1
    # Deleted charts are no longer advertised. Immutable image files are retained for in-flight readers.
    for manifest in output.glob('*.json'):
        if manifest.stem.isdigit() and manifest.stem not in active:
            try:
                doc = json.loads(manifest.read_bytes())
                if doc.get('status') == 'ready':
                    doc['status'] = 'unavailable'; doc['pages'] = []; atomic(manifest, json.dumps(doc).encode())
            except (OSError, ValueError):
                pass
    atomic(state / 'stamps.json', json.dumps(active, sort_keys=True).encode())
    return {'rendered': done, 'unchanged': unchanged, 'unavailable': failed}


def main():
    parser = argparse.ArgumentParser(); parser.add_argument('--config', required=True); parser.add_argument('--once', action='store_true')
    args = parser.parse_args(); config = json.loads(Path(args.config).read_bytes())
    while True:
        try:
            data = Path(config['catalogue']).read_bytes()
            if len(data) > 16 * 1024 * 1024:
                raise ValueError('Catalogue too large')
            catalogue = json.loads(data)
            if len(catalogue['charts']) > 50_000:
                raise ValueError('Catalogue too large')
            result = publish(catalogue, Path(config['source']), Path(config['output']), Path(config['state']), config['font'])
            print('Chart preview publisher:', json.dumps(result), flush=True)
        except (OSError, ValueError, KeyError, TypeError):
            print('Chart preview catalogue temporarily unavailable', flush=True)
        if args.once:
            return
        time.sleep(60)


if __name__ == '__main__':
    main()
