"""Offline, read-only chart radar study; never changes the live ranking rules.

Input is a chart-only ZIP snapshot: catalog.json plus snapshot_mc/snapshot_imd
members referenced by its chart rows. Outputs contain derived statistics only.
"""
import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import json
import math
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'server/chart_preview'))
sys.path.insert(0, str(ROOT / 'server/qqbot'))
from render import Timeline, beat, parse
from imd import parse_imd
from b30_data import level

VERSION = 'study-3-heads-4s-user-corners-imd-speed-zero'
AXES = [
    ('notes', '物量', '个'), ('chord', '同押', '组'),
    ('peak', '爆发', 'notes/s'), ('hold', '长按复合', '条'),
    ('slide', '滑键', '点/s'), ('speed', '变速', '次/min'),
]


def sign(value):
    return 0 if abs(value) < 1e-7 else 1 if value > 0 else -1


def slide_points(note):
    """Orthogonal projection used by RM preview; turns plus arrow endpoint.

    Example shapes confirmed by the user: up/right=2, right=1,
    up/right/up/left=4. Redundant collinear control points are ignored.
    """
    points = list(note.points) or [(note.start, note.x)]
    directions = []
    lateral = False
    for (t1, x1), (t2, x2) in zip(points, points[1:]):
        if sign(t2 - t1):
            directions.append(('v', sign(t2 - t1)))
        if sign(x2 - x1):
            directions.append(('h', sign(x2 - x1)))
            lateral = True
    # A standalone flick is one arrow endpoint. For a segmented note an
    # explicitly stored tail arrow can add the final outgoing direction.
    flag = note.tail_direction if note.points else note.direction
    arrow = ('h', 1) if flag & 2 else ('h', -1) if flag & 8 else ('v', 1) if flag & 4 else ('v', -1) if flag & 16 else None
    if arrow is not None:
        directions.append(arrow)
    if not lateral and arrow is None:
        return 0
    compact = [d for i, d in enumerate(directions) if i == 0 or d != directions[i-1]]
    return len(compact)  # (number of turns = segments-1) + one endpoint


def peak_density(times, seconds=4.0):
    """Maximum head count in a half-open continuous [start,start+window)."""
    times = sorted(times)
    end = best = 0
    best_start = times[0]
    for start in range(len(times)):
        while end < len(times) and times[end] - times[start] < seconds - 1e-9:
            end += 1
        if end - start > best:
            best, best_start = end - start, times[start]
    return best / seconds, best, best_start


def speed_changes(chart, document, first, last):
    """Count actual BPM/HS/scroll changes, merging coincident events."""
    events = defaultdict(dict)
    for at, value in chart.bpms:
        events[round(at, 9)]['bpm'] = value
    unknown = 0
    for row in document.get('effect', []):
        for key in ('hs', 'scroll'):
            if key not in row:
                continue
            value = row[key]
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
                unknown += 1
                continue
            events[round(beat(row['beat']), 9)][key] = value
    current = {'bpm': chart.bpms[0][1], 'hs': 1.0, 'scroll': 1.0}
    counts = Counter()
    change_beats = []
    for at, values in sorted(events.items()):
        changed = [key for key, value in values.items() if not math.isclose(value, current[key], rel_tol=1e-9, abs_tol=1e-9)]
        current.update(values)
        if changed and first + 1e-8 < at <= last + 1e-8:
            counts.update(changed)
            change_beats.append(at)
    return len(change_beats), dict(counts), unknown


def analyze(chart, document):
    clock = Timeline(chart.bpms)
    first, last = min(n.start for n in chart.notes), max(n.end for n in chart.notes)
    duration = clock.seconds(last) - clock.seconds(first)
    if duration <= 0:
        raise ValueError('Non-positive chart duration')
    times = [clock.seconds(n.start) for n in chart.notes]
    groups = Counter(round(n.start, 8) for n in chart.notes)
    sizes = Counter(groups.values())
    holds = sum(bool(n.points) and n.end > n.start and all(abs(x-n.x) < 1e-7 for _, x in n.points) and not n.direction and not n.tail_direction for n in chart.notes)
    points = sum(slide_points(n) for n in chart.notes)
    peak, peak_count, peak_start = peak_density(times)
    # IMD BPM is still used to time the other measurements. Its speed attribute
    # is set to zero for display, with a separate flag marking it unmeasured.
    if chart.source_format == 'IMD':
        speed, channels, unknown = 0, {}, 0
    else:
        speed, channels, unknown = speed_changes(chart, document, first, last)
    return {
        'notes': len(chart.notes), 'chord': sizes[3] + sizes[4],
        'peak': peak, 'hold': holds, 'slide': points / duration,
        'speed': speed * 60 / duration,
        'speed_status': 'disabled_imd_zero' if chart.source_format == 'IMD' else 'ready',
        'duration_s': duration,
        'chord3': sizes[3], 'chord4': sizes[4], 'chord5': sizes[5], 'chord6': sizes[6],
        'slide_points': points, 'speed_events': speed, 'speed_channels': channels,
        'unknown_speed_effects': unknown,
        'peak_count': peak_count, 'peak_start_s': peak_start-clock.seconds(first),
        'peak_end_s': peak_start-clock.seconds(first)+4,
    }


def run(snapshot, output):
    output.mkdir(parents=True, exist_ok=True)
    rows, errors, skipped = [], [], []
    with zipfile.ZipFile(snapshot) as archive:
        catalogue = json.loads(archive.read('catalog.json'))
        for row in catalogue['charts']:
            converted = level(row.get('level'), row.get('version', ''))
            if converted is None or not 10 <= converted <= 15:
                skipped.append(row['cid'])
                continue
            try:
                mc = archive.read(row['snapshot_mc'])
                if hashlib.sha256(mc).hexdigest() != row['snapshot_mc_sha256']:
                    raise ValueError('MC snapshot hash mismatch')
                doc = json.loads(mc)
                source = mc
                if row.get('snapshot_imd'):
                    source = archive.read(row['snapshot_imd'])
                    if hashlib.sha256(source).hexdigest() != row['snapshot_imd_sha256']:
                        raise ValueError('IMD snapshot hash mismatch')
                    chart = parse_imd(source, row.get('keys'))
                else:
                    chart = parse(mc, row.get('keys'))
                result = {k: row.get(k) for k in ('cid', 'sid', 'title', 'artist', 'version', 'level', 'server_hash')}
                result.update(normalized_level=converted, keys=chart.keys,
                              source_format=chart.source_format,
                              source_sha256=hashlib.sha256(source).hexdigest(),
                              **analyze(chart, doc))
                rows.append(result)
            except (ValueError, KeyError, TypeError, OSError, IndexError) as exc:
                errors.append({'cid': row['cid'], 'title': row.get('title'), 'error': str(exc)})
    # These are exploratory anchors, not a permanent game difficulty scale.
    def eligible(row, axis):
        return row[axis] is not None and (axis != 'speed' or row['speed_status'] == 'ready')
    anchors = {str(keys): {axis: max((r[axis] for r in rows if r['keys'] == keys and eligible(r, axis)), default=0) for axis, _, _ in AXES} for keys in sorted({r['keys'] for r in rows})}
    for row in rows:
        row['radar'] = {axis: (None if row[axis] is None else 100 * row[axis] / anchors[str(row['keys'])][axis] if anchors[str(row['keys'])][axis] else 0) for axis, _, _ in AXES}
    def select(candidates):
        return {axis: [r['cid'] for r in sorted((r for r in candidates if eligible(r, axis)), key=lambda r: (-r[axis], r['cid']))[:5]] for axis, _, _ in AXES}
    selections = select(rows)
    by_keys = {str(keys): select([r for r in rows if r['keys'] == keys]) for keys in sorted({r['keys'] for r in rows})}
    result = {'algorithm': VERSION, 'snapshot_sha256': hashlib.sha256(snapshot.read_bytes()).hexdigest(),
              'date': '2026-10-30', 'source_count': len(catalogue['charts']),
              'excluded_hidden': catalogue.get('excluded_hidden', 0),
              'skipped_level_count': len(skipped), 'errors': errors,
              'axes': AXES, 'anchors': anchors, 'top5': selections,
              'top5_by_keys': by_keys, 'charts': rows}
    (output/'analysis.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    flat = [{k: v for k, v in r.items() if k not in ('radar', 'speed_channels')} for r in rows]
    with (output/'all-charts.csv').open('w', newline='', encoding='utf-8-sig') as stream:
        writer = csv.DictWriter(stream, fieldnames=list(flat[0]))
        writer.writeheader(); writer.writerows(flat)
    print(json.dumps({'analyzed': len(rows), 'excluded_hidden': result['excluded_hidden'], 'skipped_level': len(skipped), 'errors': errors, 'keys': dict(Counter(r['keys'] for r in rows))}, ensure_ascii=False))
    for axis, label, unit in AXES:
        print(label)
        for cid in selections[axis]:
            row = next(r for r in rows if r['cid'] == cid)
            print(f"  {cid} | {row['title']} | Lv.{row['normalized_level']:g} | {row['keys']}K | {row[axis]:.4f} {unit}")
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--snapshot', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    run(args.snapshot, args.output)
