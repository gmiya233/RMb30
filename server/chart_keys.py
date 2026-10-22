"""Extract key counts without changing charts. Ambiguous slide grids stay unknown."""
import json
from pathlib import Path
import re
import unicodedata

VERSION = re.compile(r'(?<![0-9])([1-9][0-9]?)\s*(?:k(?![a-z])|键)', re.I)


def version_keys(version):
    counts = {int(m.group(1)) for m in VERSION.finditer(unicodedata.normalize('NFKC', version or ''))}
    return next(iter(counts)) if len(counts) == 1 else 0


def extract(chart):
    meta = chart.get('meta', {})
    mode = meta.get('mode')
    declared = version_keys(meta.get('version', ''))
    column = meta.get('mode_ext', {}).get('column', 0)
    if mode == 0 and isinstance(column, int) and 1 <= column <= 64:
        return column, 'metadata'
    if mode != 7:
        return declared, 'version' if declared else 'unknown'
    positions = [n['x'] for n in chart.get('note', [])
                 if isinstance(n.get('x'), (int, float)) and 'w' in n]
    candidates = []
    if len(positions) >= 16:
        for keys in (4, 5, 6):
            centers = [(lane + .5) * 256 / keys for lane in range(keys)]
            matched = [min(abs(x - center) for center in centers) <= 3.5 for x in positions]
            lanes = {min(range(keys), key=lambda lane: abs(x - centers[lane]))
                     for x, ok in zip(positions, matched) if ok}
            if sum(matched) / len(matched) >= .98 and len(lanes) >= min(keys, 3):
                candidates.append(keys)
    if len(candidates) == 1:
        count = candidates[0]
        if declared and declared != count:
            return 0, 'conflicting_metadata'
        return count, 'slide_grid'
    return declared, 'version' if declared else 'unknown'


def supplement(database, charts, previous):
    base = database.resolve().parent
    old = {(r['cid'], r['server_hash']): r for r in previous.get('charts', [])}
    rows = []
    for chart in charts:
        identity = chart['cid'], chart['server_hash']
        if identity in old and (old[identity].get('keys', 0) > 0 or old[identity].get('inspected')):
            row = old[identity]
        else:
            row = {'cid': identity[0], 'server_hash': identity[1], 'keys': 0,
                   'keys_source': 'unknown'}
            relative = Path(chart['path'])
            candidates = [base / relative / chart['mc_name'], base / relative,
                          base.parent / relative / chart['mc_name'], base.parent / relative]
            for path in candidates:
                try:
                    # Only trusted database-relative chart paths inside its storage tree.
                    if not path.resolve().is_relative_to(base.parent) or path.suffix != '.mc' or not path.is_file():
                        continue
                    if path.stat().st_size > 16 * 1024 * 1024:
                        continue
                    count, source = extract(json.loads(path.read_text(encoding='utf-8-sig')))
                    row.update(keys=count, keys_source=source, inspected=True)
                    break
                except (OSError, ValueError, TypeError, KeyError):
                    continue
            if not row['keys'] and row['keys_source'] == 'unknown':
                count = version_keys(chart.get('version', ''))
                if count:
                    row.update(keys=count, keys_source='version')
        if row['keys']:
            chart.update(keys=row['keys'], keys_source=row['keys_source'])
        rows.append(row)
    return rows
