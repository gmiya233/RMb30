"""Current catalogue ceiling under the default rating-v3 rules; no player data."""
import math
from b30_data import identity, level

MAX_JUDGE_MULTIPLIER = 1.04  # E; accuracy >=99.8 has factor 1.00.


def calculate(charts):
    charts = list(charts)
    fresh = {identity(c) for c in charts if c.get('promote') or str(c.get('path', '')).startswith('promote/')}
    best = {}
    invalid = 0
    for chart in charts:
        value = level(chart.get('level'), chart.get('version'))
        if value is None or not math.isfinite(value):
            invalid += 1
            continue
        row = dict(cid=chart['cid'], sid=chart.get('sid', 0), title=chart.get('title') or '未命名',
                   version=chart.get('version') or '', level=value, rating=value * MAX_JUDGE_MULTIPLIER)
        key = identity(chart)
        if key not in best or (-row['rating'], row['cid']) < (-best[key]['rating'], best[key]['cid']):
            best[key] = row
    old, new = [], []
    for key, row in best.items():
        (new if key in fresh else old).append(row)
    order = lambda row: (-row['rating'], row['cid'])
    old, new = sorted(old, key=order)[:20], sorted(new, key=order)[:10]
    old_sum, new_sum = math.fsum(r['rating'] for r in old), math.fsum(r['rating'] for r in new)
    return dict(rating=(old_sum + new_sum) / 30, o=old_sum / 20, n=new_sum / 10,
                old=old, new=new, invalid_charts=invalid, songs=len(best))


def summary(board):
    return (f"当前曲库理论最高 rating：{board['rating']:.4f}\n"
            f"O20：{board['o']:.4f}（{len(board['old'])}/20） · N10：{board['n']:.4f}（{len(board['new'])}/10）\n"
            '按全部键数、E判、准度≥99.8%及当前定级计算；同歌去重、缺位补0，随曲库更新。')
