"""Conservative B30 gain estimates, using published summaries and bound accounts only."""
import math
import re
from statistics import median
from b30_data import identity, level
from ranking_queries import read_json, text

MULTIPLIERS=(0,.97,.985,1,1.02,1.04)


def factor(accuracy):
    if accuracy>=99.8:return 1.
    if accuracy>=99.5:return (1+(accuracy-99.5)/30)/1.01
    return (accuracy/99.5)**2/1.01


def recommend(charts,summary):
    charts={r['cid']:r for r in charts}
    fresh={identity(c) for c in charts.values() if c.get('promote') or str(c.get('path','')).startswith('promote/')}
    scores={}
    evidence={}
    for score in summary['old']+summary['new']:
        chart=charts.get(score.get('cid'))
        if chart is None:continue
        grade=level(chart.get('level'),chart.get('version'));keys=chart.get('keys')
        accuracy=score.get('accuracy');judge=score.get('judge');rating=score.get('rating')
        if grade is None or type(judge)!=int or not 1<=judge<=5:continue
        if any(type(v) not in (int,float) or not math.isfinite(v) for v in (accuracy,rating)) or not 0<=accuracy<=100 or rating<0:continue
        key=identity(chart)
        entry=dict(score,grade=grade,keys=keys,chart=chart)
        if key not in scores or rating>scores[key]['rating']:scores[key]=entry
    for s in scores.values():
        if s['keys'] in (4,5,6):evidence.setdefault(s['keys'],[]).append(s)
    def total(rows):
        old=sorted((s['rating'] for key,s in rows.items() if key not in fresh),reverse=True)[:20]
        new=sorted((s['rating'] for key,s in rows.items() if key in fresh),reverse=True)[:10]
        return (math.fsum(old)+math.fsum(new))/30
    baseline=total(scores)
    # Reject incomplete/mismatched summaries instead of fabricating gains.
    if abs(baseline-summary['rating'])>1e-8:raise ValueError('摘要与当前曲库不一致，请稍后重试。')
    candidates=[];caps={}
    for keys,history in evidence.items():
        stable=sorted([s['grade'] for s in history if s['accuracy']>=97],reverse=True)
        cap=stable[min(2,len(stable)-1)] if stable else min(s['grade'] for s in history)
        caps[keys]=cap
        for chart in charts.values():
            if chart.get('search_visible',True) is not True or chart.get('keys')!=keys:continue
            grade=level(chart.get('level'),chart.get('version'))
            if grade is None or grade>cap:continue
            key=identity(chart);current=scores.get(key)
            if current and current['keys']!=keys:continue
            if current is None and len(stable)<3:continue
            # Calibrate each new chart against close levels in the same key mode.
            neighbors=sorted(history,key=lambda s:abs(s['grade']-grade))[:5]
            if current:
                judge=current['judge'];accuracy=min(99.8,current['accuracy']+.3)
            else:
                judge=int(median(s['judge'] for s in neighbors))
                accuracy=min(99.5,median(s['accuracy'] for s in neighbors)+.3)
            value=grade*factor(accuracy)*MULTIPLIERS[judge]
            if current and value<=current['rating']+.005:continue
            changed=dict(scores);changed[key]=dict(rating=value)
            gain=total(changed)-baseline
            if gain<.0001:continue
            candidates.append(dict(chart=chart,grade=grade,keys=keys,judge=judge,accuracy=accuracy,
                                   rating=value,gain=gain,existing=current is not None))
    # A song can offer several charts; only propose its best achievable option once.
    candidates.sort(key=lambda r:(-r['gain'],r['grade'],r['chart']['cid']))
    unique={}
    for row in candidates:unique.setdefault(identity(row['chart']),row)
    return list(unique.values())[:5],caps


class PushRecommendations:
    def __init__(self,queries,b30):self.queries,self.b30=queries,b30
    def execute(self,group,member,content):
        from b30_commands import command_parts
        if command_parts(content)[1]:return '用法：直接发送“推分推荐”。'
        qq=self.b30.bindings.get(group,member)
        if not qq:return '请先发送“绑定 你的QQ号”，并在App中同步成绩，再来查询推分推荐。'
        try:
            players,stamp=self.queries.board()
            player=next((p for p in players if p['qq']==qq),None)
            if player is None:return '暂时没有你的有效云端成绩，请先在App中上传。'
            status=read_json(self.queries.root/'status.json',100000);revision=status['revision']
            if not re.fullmatch('[a-f0-9]{64}',revision):raise ValueError('摘要版本无效')
            doc=read_json(self.b30.summaries/revision/(qq+'.json'),300000)
            if doc.get('schema')!=1 or doc.get('qq')!=qq or doc.get('revision')!=revision or doc.get('server')!=self.queries.catalogue()[0]:raise ValueError('摘要版本不一致')
            if abs(doc['rating']-player['rating'])>1e-8:raise ValueError('新成绩的摘要正在更新')
            import time
            if time.time()*1000-doc['updated']>300000: return '成绩榜单更新延迟，请稍后再查推分推荐。'
            rows,caps=recommend(self.queries.catalogue()[1].values(),doc)
            lines=[f"{text(player['name'])} · 推分推荐",f"当前 rating {player['rating']:.4f}",
                   '保守等级上限：'+(' / '.join(f'{k}K ≤{v:g}级' for k,v in sorted(caps.items())) or '数据不足')]
            if not rows:
                return '\n'.join(lines+['暂未找到有足够成绩依据的稳妥推分谱面。先巩固已玩等级，上传更多高准度成绩后再试；不自动推荐更高等级。'])
            for i,r in enumerate(rows,1):
                c=r['chart'];section='N10' if c.get('promote') or identity(c) in {identity(x) for x in self.queries.catalogue()[1].values() if x.get('promote')} else 'O20'
                lines.extend([f"\n{i}. {text(c.get('title','未命名'),46)}",
                    f"ID {c['cid']} · {r['keys']}K · Lv.{r['grade']:g} · {section} · "+('巩固已有成绩' if r['existing'] else '同级或更低的新选择'),
                    f"目标 {'ABCDE'[r['judge']-1]}判 / {r['accuracy']:.2f}% → 单曲 {r['rating']:.4f}",
                    f"预计综合 rating +{r['gain']:.4f}"])
            return '\n'.join(lines+['\n仅依据已上传B30估算，未考虑未上传成绩或同等级谱面难度差异；各条提升独立计算，不能直接相加。先试读谱，吃力就降级。',stamp])
        except (OSError,ValueError,KeyError,TypeError,AttributeError):
            return '推分推荐摘要暂未就绪，请先同步云端成绩，稍后再试。'
