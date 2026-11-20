"""Recompute derived radar values from chart-only snapshots and an official reference.

No chart/audio files are extracted or modified. The MCZ contains one HD IMD
for each of 4/5/6K. Other dimensions retain the baseline scale until calibrated.
"""
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import sys
import zipfile

from analyze_chart_radar import VERSION, analyze, parse, parse_imd
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
from chart_keys import extract

SCALE = 'per-keys-chart-references-v5'
CALIBRATED = {'notes': 0, 'peak': 2, 'slide': 5}


def reference_profile(package):
    profiles = {}
    with zipfile.ZipFile(package) as archive:
        for keys in (4, 5, 6):
            names = [n for n in archive.namelist() if n.lower().endswith(f'_{keys}k_hd.imd')]
            if len(names) != 1:
                raise ValueError(f'Expected exactly one {keys}K HD IMD')
            name = names[0]
            if archive.getinfo(name).file_size > 16 * 1024 * 1024:
                raise ValueError('Reference exceeds size limit')
            raw = archive.read(name)
            stats = analyze(parse_imd(raw, keys), {})
            if stats['chord'] != 0:
                raise ValueError('Reference no longer matches the agreed zero chord count')
            profiles[str(keys)] = {
                'source_sha256': hashlib.sha256(raw).hexdigest(),
                'difficulty': 'HD', 'reference_level': 14,
                'targets': {'notes': 90, 'chord': 0, 'peak': 90, 'speed': 0, 'slide': 90},
                'raw': stats,
                'anchors': {axis: stats[axis] / .9 for axis in CALIBRATED},
            }
    return profiles


def calibrated_values(stats, profile, baseline):
    result = list(baseline)
    axes = dict(CALIBRATED)
    if 'speed' in profile['anchors']:
        axes['speed'] = 3
    if 'hold' in profile['anchors']:
        axes['hold'] = 4
    for axis, index in axes.items():
        anchor = profile['anchors'][axis]
        if not math.isfinite(anchor) or anchor <= 0:
            raise ValueError('Invalid calibration anchor')
        result[index] = stats[axis] / anchor * 100
    return result


def speed_reference_profile(package):
    with zipfile.ZipFile(package) as archive:
        names = [n for n in archive.namelist() if n.lower().endswith('.mc')]
        if len(names) != 1 or archive.getinfo(names[0]).file_size > 16 * 1024 * 1024:
            raise ValueError('Speed reference must contain exactly one bounded MC chart')
        raw = archive.read(names[0])
    document = json.loads(raw)
    keys, _ = extract(document)
    if keys not in (4, 5, 6):
        raise ValueError('Speed reference key count is ambiguous')
    stats = analyze(parse(raw, keys), document)
    if stats['speed'] <= 0 or stats['unknown_speed_effects']:
        raise ValueError('Speed reference has no usable complete speed measurement')
    return {'title': document.get('meta', {}).get('song', {}).get('title', 'New Astronomas'),
            'keys': keys, 'target': 90,
            'source_sha256': hashlib.sha256(raw).hexdigest(),
            'package_sha256': hashlib.sha256(Path(package).read_bytes()).hexdigest(),
            'raw': stats, 'anchor': stats['speed'] / .9}


def hold_reference_profiles(package):
    profiles = {}
    with zipfile.ZipFile(package) as archive:
        for keys in (4, 5, 6):
            names = [n for n in archive.namelist() if n.lower().endswith(f'_{keys}k_mx.imd')]
            if len(names) != 1 or archive.getinfo(names[0]).file_size > 16 * 1024 * 1024:
                raise ValueError(f'Expected exactly one bounded {keys}K MX IMD')
            raw = archive.read(names[0])
            stats = analyze(parse_imd(raw, keys), {})
            if stats['hold'] <= 0:
                raise ValueError('Hold reference has no straight holds')
            profiles[str(keys)] = {'title': '覆写者 (#Overwritter_)', 'keys': keys,
                'difficulty': 'MX', 'target': 110, 'raw': stats,
                'source_sha256': hashlib.sha256(raw).hexdigest(),
                'anchor': stats['hold'] / 1.1}
    return {'package_sha256': hashlib.sha256(Path(package).read_bytes()).hexdigest(),
            'profiles': profiles}


def calibrate(baseline, snapshot, package, old_anchors, speed_package, hold_package):
    if baseline.get('scale') != 'per-keys-top5-mean-candidate':
        raise ValueError('Use the unchanged pre-calibration baseline; do not rescale twice')
    result = copy.deepcopy(baseline)
    profiles = reference_profile(package)
    speed_reference = speed_reference_profile(speed_package)
    profiles[str(speed_reference['keys'])]['anchors']['speed'] = speed_reference['anchor']
    hold_references = hold_reference_profiles(hold_package)
    for keys, reference in hold_references['profiles'].items():
        profiles[keys]['anchors']['hold'] = reference['anchor']
    hold_anchors = {}
    with zipfile.ZipFile(snapshot) as archive:
        for row in result['charts']:
            cid, keys = row['cid'], row['keys']
            raw = archive.read(f'{cid}.imd' if row['source_format'] == 'IMD' else f'{cid}.mc')
            chart = parse_imd(raw, keys) if row['source_format'] == 'IMD' else parse(raw, keys)
            stats = analyze(chart, {} if row['source_format'] == 'IMD' else json.loads(raw))
            # Independently verify this is the same data as the old snapshot.
            # The only measurement change is +1 for each standalone flick.
            singles = sum(not n.points and bool(n.direction & (2 | 4 | 8 | 16)) for n in chart.notes)
            old_slide = (stats['slide_points'] - singles) / stats['duration_s']
            for axis, index in CALIBRATED.items():
                previous = old_slide if axis == 'slide' else stats['head_peak'] if axis == 'peak' else stats[axis]
                expected = row['values'][index] * old_anchors[str(keys)][axis] / 100
                if not math.isclose(previous, expected, rel_tol=1e-7, abs_tol=1e-7):
                    raise ValueError(f'Baseline measurement mismatch for CID {cid}, {axis}')
            if stats['hold_count'] and row['values'][4]:
                anchor = stats['hold_count'] * 100 / row['values'][4]
                if str(keys) in hold_anchors and not math.isclose(hold_anchors[str(keys)], anchor, rel_tol=1e-7):
                    raise ValueError('Hold baseline mismatch')
                hold_anchors[str(keys)] = anchor
            row['values'] = calibrated_values(stats, profiles[str(keys)], row['values'])
            row['raw_metrics'] = {axis: stats[axis] for axis in (*CALIBRATED, 'speed', 'hold')}
            row['source_sha256'] = hashlib.sha256(raw).hexdigest()
    for keys, profile in profiles.items():
        profile['values'] = calibrated_values(profile['raw'], profile, [0] * 6)
    result.update(scale=SCALE, algorithm=VERSION, calibration={
        'reference': '事件视界 (Event Hor!zon)',
        'package_sha256': hashlib.sha256(Path(package).read_bytes()).hexdigest(),
        'profiles': profiles,
        'uncalibrated_axes': ['chord'],
        'hold_references': hold_references,
        'hold_measurement': 'maximum-mean-active-straight-holds-in-continuous-8-seconds',
        'speed_reference': speed_reference,
        'speed_uncalibrated_keys': [k for k in (4, 5, 6) if k != speed_reference['keys']],
        'single_flick_points': 2,
        'burst_measurement': 'heads-plus-timed-slide-points-in-4-seconds',
    })
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('baseline', 'snapshot', 'reference', 'speed-reference', 'hold-reference', 'baseline-anchors', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    baseline = json.loads(args.baseline.read_text(encoding='utf-8-sig'))
    anchors = json.loads(args.baseline_anchors.read_text(encoding='utf-8-sig'))['anchors']
    result = calibrate(baseline, args.snapshot, args.reference, anchors, args.speed_reference, args.hold_reference)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf-8')
    print(json.dumps({'charts': len(result['charts']), 'scale': SCALE,
                      'references': {k: p['values'] for k, p in result['calibration']['profiles'].items()}}, ensure_ascii=False))


if __name__ == '__main__':
    main()
