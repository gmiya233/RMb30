"""Prepare B30 from the ranking worker's already-rated best scores."""
import hashlib
import math
import re


def level(raw, version):
    sc = re.search(r'(?i)(?<![A-Z])SC\s*([1-9][0-9]*)(?![0-9.])', version or '')
    if sc:
        value = int(sc[1])
        return 12 if value <= 4 else 13 if value <= 13 else 15
    try:
        raw = float(raw)
    except (ValueError, TypeError):
        return None
    if not math.isfinite(raw) or raw <= 0:
        return None
    if raw < 16:
        return raw
    if raw != int(raw) or raw > 35:
        return None
    return next(rm for maximum, rm in [(16, 8), (18, 9), (20, 10), (23, 11), (26, 12), (29, 13), (32, 14), (35, 15)] if raw <= maximum)


def identity(chart):
    sid = chart.get('sid', 0)
    return ('sid', sid) if isinstance(sid, int) and sid > 0 else ('cid', chart['cid'])


def artwork_candidates(chart):
    candidates = []
    for path in [chart.get('cover'), chart.get('background')]:
        clean = str(path or '').replace('\\', '/').strip('/')
        if clean and '..' not in clean.split('/'):
            candidates.append(hashlib.sha256(clean.encode()).hexdigest())
    candidates.extend(chart.get('artwork_fallbacks', []))
    return list(dict.fromkeys(candidates))


def prepare(player, catalogue, revision, updated):
    charts = {c['cid']: c for c in catalogue['charts']}
    fresh = {identity(c) for c in charts.values() if c.get('promote') or str(c.get('path', '')).startswith('promote/')}
    best = {}
    for score in player['best']:
        chart = charts.get(score['cid'])
        if not chart:
            continue
        key = identity(chart)
        order = lambda s: (-s['rating'], -s['accuracy'], -s['score'], s['cid'])
        if key not in best or order(score) < order(best[key]):
            best[key] = score
    old, new = [], []
    for key, score in best.items():
        chart = charts[score['cid']]
        candidates = artwork_candidates(chart)
        row = dict(score, title=chart.get('title') or '未命名', version=chart.get('version') or '',
                   level=level(chart.get('level'), chart.get('version')),
                   artwork=candidates[0] if candidates else '', artworks=list(dict.fromkeys(candidates)))
        (new if key in fresh else old).append(row)
    order = lambda s: (-s['rating'], -s['accuracy'], -s['score'], s['cid'])
    old, new = sorted(old, key=order)[:20], sorted(new, key=order)[:10]
    old_rating = sum(s['rating'] for s in old) / 20
    new_rating = sum(s['rating'] for s in new) / 10
    total = (old_rating * 20 + new_rating * 10) / 30
    for actual, expected in [(total, player['rating']), (old_rating, player['o']), (new_rating, player['n'])]:
        if not math.isclose(actual, expected, rel_tol=1e-12, abs_tol=1e-12):
            raise ValueError('B30 does not match Java rating snapshot')
    if len(old) != player['oc'] or len(new) != player['nc']:
        raise ValueError('B30 does not match Java slot counts')
    return dict(schema=1, server=catalogue['server'], revision=revision, updated=updated,
                qq=player['qq'], name=player['name'], player_title=str(player.get('title') or '')[:128], rating=total, o=old_rating, n=new_rating, old=old, new=new)
