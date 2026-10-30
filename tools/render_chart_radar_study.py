"""Render the exploratory chart measurements with Matplotlib, not game scores."""
import argparse
import base64
from collections import Counter
import html
import io
import json
import math
import os
from pathlib import Path
import sys
import textwrap
import zipfile

ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault('MPLCONFIGDIR', str(ROOT / '.cache/matplotlib'))
sys.path.insert(0, str(ROOT / 'tools/python-libs'))
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties
from matplotlib.backends.backend_pdf import PdfPages

FONT = FontProperties(fname='C:/Windows/Fonts/msyh.ttc')
plt.rcParams.update({'font.family': FONT.get_name(), 'axes.unicode_minus': False,
                     'svg.fonttype': 'path', 'pdf.fonttype': 42})
LABELS = ['物量', '同押', '爆发', '变速', '长按复合', '滑键']
AXES = ['notes', 'chord', 'peak', 'speed', 'hold', 'slide']
CATEGORY_AXES = ['notes', 'chord', 'peak', 'hold', 'slide', 'speed']
COLORS = ['#097e88', '#a26710', '#c05158', '#9a48a2', '#6b63b7', '#2476b1']
BG, INK, MUTED = '#f3f7f8', '#203342', '#637987'


def display_title(title):
    # Raw database title remains in the table/CSV. Shorten only known filename suffixes.
    import re
    return re.sub(r'(?i)_4k_(?:hd|hard)$', '', title)


def wrap(text, width, max_lines=None):
    lines = []; line = ''; units = 0
    for char in text:
        size = 2 if ord(char) > 255 else 1
        if units + size > width:
            lines.append(line); line = ''; units = 0
        line += char; units += size
    if line: lines.append(line)
    if max_lines and len(lines) > max_lines:
        lines = lines[:max_lines]; lines[-1] = lines[-1][:-1] + '…'
    return '\n'.join(lines)


def raw(row, axis):
    value = row[axis]
    if value is None:
        return '暂不统计'
    if axis in ('notes', 'chord', 'hold'):
        return f'{value:,}'
    return f'{value:.2f}'


def radar(ax, row, highlight, title=True, compact=False):
    theta = [i * math.tau / 6 for i in range(6)]
    values = [row['radar'][key] if row['radar'][key] is not None else math.nan for key in AXES]
    labels = [label if row['radar'][key] is not None else label+'\n（暂不统计）' for key,label in zip(AXES,LABELS)]
    closed = theta + [theta[0]]
    color = COLORS[AXES.index(highlight)]
    ax.set_theta_offset(math.pi/2); ax.set_theta_direction(-1)
    ax.set_ylim(0,100)
    ax.set_xticks(theta, labels, fontsize=10 if compact else 12, color=INK)
    ax.tick_params(axis='x', pad=8)
    ax.set_yticks([25,50,75,100], ['25','50','75','100'], fontsize=6 if compact else 8, color='#8da0ac')
    ax.set_rlabel_position(30)
    ax.grid(color='#d6e2e8', linewidth=.65)
    ax.spines['polar'].set_color('#c7d9e2')
    ax.set_facecolor('white')
    ax.plot(closed,values+[values[0]],color=color,lw=1.9)
    if all(math.isfinite(v) for v in values):
        ax.fill(closed,values+[values[0]],color=color,alpha=.16)
    ax.scatter(theta,values,color=color,s=12,zorder=3)
    if title:
        ax.set_title(wrap(display_title(row['title']),35,3), fontsize=10 if compact else 13,
                     fontproperties=FONT, color=INK, pad=27)


def detail(row, label, axis, rank):
    unit={'notes':'个','chord':'组','peak':'notes/s','hold':'条','slide':'点/s','speed':'次/min'}[axis]
    return f"#{rank}  {raw(row,axis)} {unit}  ·  Lv.{row['normalized_level']:g} · {row['keys']}K\nCID {row['cid']} · {row['source_format']}"


def single(row, axis, path):
    fig=plt.figure(figsize=(7.8,8.5),facecolor=BG)
    fig.text(.07,.95,wrap(display_title(row['title']),55,3),va='top',fontsize=15,fontproperties=FONT,color=INK)
    fig.text(.07,.865,f"Lv.{row['normalized_level']:g}  ·  {row['keys']}K  ·  CID {row['cid']}  ·  {row['source_format']}",fontsize=11,color=MUTED)
    ax=fig.add_axes([.17,.29,.66,.52],projection='polar')
    radar(ax,row,axis,title=False)
    units=['个','组','notes/s','次/min','条','点/s']
    for i, key in enumerate(AXES):
        x=.09+(i%3)*.30; y=.19-(i//3)*.065
        fig.text(x,y,LABELS[i],fontsize=10,color=MUTED)
        unit=units[i] if row[key] is not None else ''
        fig.text(x,y-.027,f'{raw(row,key)} {unit}',fontsize=12,color=COLORS[i])
    fig.text(.07,.035,'同键数样本最大值 = 100 · IMD变速按0显示 · 非玩家能力／非正式定级',fontsize=9,color=MUTED,fontproperties=FONT)
    fig.savefig(path,dpi=150,facecolor=BG)
    plt.close(fig)


def category(doc, axis, keys=None):
    rows={r['cid']:r for r in doc['charts']}
    index=AXES.index(axis); label=LABELS[index]
    ids=doc['top5'][axis] if keys is None else doc['top5_by_keys'][str(keys)][axis]
    fig=plt.figure(figsize=(22,6.4),facecolor=BG)
    heading=f'{label} TOP 5' + (' · 全键数选样' if keys is None else f' · {keys}K')
    fig.text(.025,.94,heading,fontsize=23,fontproperties=FONT,color=INK,weight='bold')
    fig.text(.025,.88,'10–15级 · 六轴均为谱面属性 · 各键数独立取样本最大值100 · 原始数量见卡片和附表',fontsize=11,color=MUTED,fontproperties=FONT)
    for i,cid in enumerate(ids):
        row=rows[cid]
        ax=fig.add_axes([.035+i*.195,.24,.145,.46],projection='polar')
        radar(ax,row,axis,compact=True)
        fig.text(.107+i*.195,.13,detail(row,label,axis,i+1),ha='center',va='top',fontsize=11,color=COLORS[index],fontproperties=FONT)
    fig.text(.025,.035,'爆发：连续4秒峰值；同押：三押＋四押；滑键：转角＋箭头终点／秒；IMD变速暂不统计，按0显示，不参与变速排名。',fontsize=11,color=MUTED,fontproperties=FONT)
    return fig


def overview(doc):
    rows={r['cid']:r for r in doc['charts']}
    fig=plt.figure(figsize=(22,32),facecolor=BG)
    fig.text(.025,.98,'Malody 谱面六维 · 六个维度各选五张',va='top',fontsize=26,fontproperties=FONT,color=INK)
    fig.text(.025,.958,'313张有效谱面 · Lv.10–15 · 2026-10-30 · 按原始指标选样，雷达按同键数样本最大值归一化',fontsize=12,fontproperties=FONT,color=MUTED)
    for j,axis in enumerate(CATEGORY_AXES):
        top=.93-j*.15
        index=AXES.index(axis)
        fig.text(.025,top,LABELS[index]+' TOP 5',fontsize=16,fontproperties=FONT,color=COLORS[index])
        for i,cid in enumerate(doc['top5'][axis]):
            row=rows[cid]
            ax=fig.add_axes([.04+i*.194,top-.112,.137,.072],projection='polar')
            radar(ax,row,axis,title=False,compact=True)
            fig.text(.107+i*.194,top-.012,wrap(display_title(row['title']),34,2),ha='center',va='top',fontsize=9,fontproperties=FONT,color=INK)
            fig.text(.107+i*.194,top-.132,f"#{i+1} · Lv.{row['normalized_level']:g} · {row['keys']}K · {raw(row,axis)}\nCID {cid}",ha='center',va='top',fontsize=10,color=COLORS[index],fontproperties=FONT)
    fig.text(.025,.018,'IMD变速暂不统计，按0显示、不参与变速排名。谱面属性研究图，非玩家能力，正式算法仍需标定。',fontsize=12,color=MUTED,fontproperties=FONT)
    return fig


def html_report(doc, output):
    rows={r['cid']:r for r in doc['charts']}
    body=[]
    for axis,label,_ in doc['axes']:
        cards=[]
        for rank,cid in enumerate(doc['top5'][axis],1):
            row=rows[cid]; title=html.escape(row['title'])
            image=base64.b64encode((output/'charts'/f'{cid}.png').read_bytes()).decode()
            scales={key:(f'{value:.1f}' if value is not None else '—') for key,value in row['radar'].items()}
            cells=''.join(f'<tr><th>{LABELS[i]}</th><td>{raw(row,key)}</td><td>{scales[key]}</td></tr>' for i,key in enumerate(AXES))
            speed_text='IMD变速暂不统计，按0显示，不参与该轴排名' if row.get('speed_status')=='disabled_imd_zero' else f'速度变化 {row["speed_events"]}次'
            cards.append(f'<article><h3>#{rank} {title}</h3><p>Lv.{row["normalized_level"]:g} · {row["keys"]}K · CID {cid} · {row["source_format"]}</p><img alt="{title} 六维图" loading="lazy" src="data:image/png;base64,{image}"><details><summary>原始数据与刻度</summary><table><tr><th>维度</th><th>原始值</th><th>雷达刻度</th></tr>{cells}</table><p>三押 {row["chord3"]}组／四押 {row["chord4"]}组；直长条 {row["hold"]}条；滑键 {row["slide_points"]}点。<br>峰值窗口：第 {row["peak_start_s"]:.2f}–{row["peak_end_s"]:.2f}秒，{row["peak_count"]}个音符；计时从首个音符开始。<br>有效时长 {row["duration_s"]:.2f}秒；{speed_text}。</p></details></article>')
        body.append(f'<section id="{axis}"><h2>{label} TOP 5</h2><div class="grid">'+''.join(cards)+'</div></section>')
    anchors=''.join('<tr><th>'+k+'K</th>'+''.join(f'<td>{v[a]:.3f}</td>' for a in AXES)+'</tr>' for k,v in doc['anchors'].items())
    methods='''<ul>
<li>范围：当前可查的服务器谱面；按现有 SC／MaV→RM 规则换算后取10～15级。313张：4K 272张、5K 6张、6K 35张。44张隐藏谱面未纳入，68张等级范围外，解析失败0张。</li>
<li>物量＝可玩音符头总数。一个长条或整条滑键起点记1；声音事件不计，长条尾与折点不重复计物量。本口径不等同于游戏最大combo。</li>
<li>同押＝三押组数＋四押组数，同一时刻四押不拆成三押。以同时开始的音符头计组，不把正在按住的长条另算一次按下。五押、六押单独记录，暂不计本轴。</li>
<li>爆发＝真实连续4秒半开窗口内最多音符头数÷4，多押的每个音符分别计数；BPM变化按分段节拍积分换算真实时间。</li>
<li>长按复合＝没有横向位移和滑出方向的直长条数量，整条计1，不按内部段数累加。</li>
<li>滑键＝中途转角＋末端箭头落点，起点和共线冗余控制点不计，单次直滑计1。用户示意图三个例子为2／1／4。密度＝点数÷首个音符至最后音符结束的秒数。</li>
<li>变速＝有效BPM、HS、scroll变化的合并事件数÷有效分钟数；同一拍只算一次，初始设置、重复相同值忽略。不把jump位置跳变、sign拍号标记当成速度变化。细小数值改变也会被计数，因此这是控制点频率，不是直接的操作难度。</li>
<li>IMD原始谱面优先于占位MC：匹配MC同名IMD，其次精确歌曲名；不任意选同文件夹其他难度。不执行皮肤或谱面脚本。按用户要求，IMD变速暂不统计：原始值和雷达值统一按0显示，雷达图正常闭合；不参与变速前五及变速锚点计算，其余五项保持原算法。</li>
<li>选样：每维按原始指标降序，各取5张CID，平分按CID排序。同张谱面可出现在多个维度。主榜跨键数选样；附带4K／5K／6K各自前五图。</li>
<li>六维半径＝该项原始值÷同键数313张样本内最大值×100；每张图使用相同口径，不对每张单独拉满。5K仅6张样本，刻度稳定性有限。这个尺度只用于本轮研究，未接入线上rating。</li>
</ul>'''
    nav=''.join(f'<a href="#{a}">{LABELS[i]}</a>' for i,a in enumerate(AXES))
    page='''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Malody 谱面六维研究</title><style>
*{box-sizing:border-box}body{margin:0;background:#f3f7f8;color:#203342;font:16px/1.7 system-ui,"Microsoft YaHei",sans-serif}main{max-width:1500px;margin:auto;padding:32px 24px}h1{font-size:clamp(26px,4vw,42px);line-height:1.3}h2{margin-top:40px;color:#097e88}h3{line-height:1.5;font-size:17px}p{color:#637987}nav{display:flex;gap:8px;flex-wrap:wrap;position:sticky;top:0;background:#f3f7f8ed;padding:12px 0;z-index:1}nav a{border-radius:22px;background:#dceff0;color:#076d77;padding:7px 18px;text-decoration:none}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:18px}article{background:white;border:1px solid #dce6eb;border-radius:16px;padding:18px;min-width:0}img{display:block;width:100%;height:auto}table{border-collapse:collapse;width:100%;font-size:14px}th,td{text-align:left;padding:7px;border-bottom:1px solid #dce6eb}details{border-top:1px solid #dce6eb;padding-top:12px}summary{cursor:pointer;color:#097e88}li{margin:9px 0}.note{border-left:4px solid #b58023;background:#fff8e9;padding:16px;border-radius:8px}section{scroll-margin-top:70px}a{color:#097e88}footer{margin-top:40px;color:#637987}</style><main>'''
    page+='<h1>Malody 谱面六维研究 · 修订版</h1><p>每个维度各选五张 · 10～15级 · 2026-10-30 · 谱面属性试算</p><div class="note">本版IMD谱面的变速暂不统计，原始值和雷达值统一按0显示，雷达图正常闭合；不参与变速排名和变速刻度计算。其余五项沿用原算法。<br>《棉花糖》（MC）的变速控制点仍约2024次／分钟，连续滚速的正式标定另行讨论。</div><nav>'+nav+'</nav>'+''.join(body)
    page+='<h2>统计规则与研究边界</h2>'+methods+'<h2>本轮100分锚点（按键数分开）</h2><table><tr><th>键数</th>'+''.join('<th>'+x+'</th>' for x in LABELS)+'</tr>'+anchors+'</table><footer>全部313张的原始数据见 all-charts.csv；完整字段、分键数Top5和源文件摘要见 analysis.json。报告包含派生统计，不包含原始谱面、账号或玩家成绩。</footer></main></html>'
    (output/'report.html').write_text(page,encoding='utf-8')


def run(output):
    doc=json.loads((output/'analysis.json').read_text(encoding='utf-8'))
    rows={r['cid']:r for r in doc['charts']}
    (output/'charts').mkdir(exist_ok=True)
    selected={cid:axis for axis,ids in reversed(list(doc['top5'].items())) for cid in ids}
    for cid,axis in selected.items(): single(rows[cid],axis,output/'charts'/f'{cid}.png')
    with PdfPages(output/'six-dimension-top5.pdf') as pdf:
        for axis in CATEGORY_AXES:
            fig=category(doc,axis)
            fig.savefig(output/f'top5-{axis}.png',dpi=125,facecolor=BG)
            pdf.savefig(fig,facecolor=BG); plt.close(fig)
    for keys in doc['top5_by_keys']:
        folder=output/f'{keys}K'; folder.mkdir(exist_ok=True)
        for axis in CATEGORY_AXES:
            fig=category(doc,axis,int(keys)); fig.savefig(folder/f'top5-{axis}.png',dpi=110,facecolor=BG); plt.close(fig)
    fig=overview(doc); fig.savefig(output/'overview.png',dpi=125,facecolor=BG); plt.close(fig)
    html_report(doc,output)
    lines=['# Malody 谱面六维：各维度前五（修订版）','', '范围：2026-10-30当前可查谱面；换算10～15级；313张成功解析。主表跨键数选样，雷达刻度按键数独立归一化。','', 'IMD谱面的变速暂不统计，原始值和雷达值统一按0显示，雷达图正常闭合；不参与变速排名与该轴锚点计算。其余五项沿用原算法。','']
    for axis,label,unit in doc['axes']:
        lines += ['## '+label,'','| 排名 | 歌曲 | CID | 等级 | 键数 | 原始值 |','|---|---|---|---|---|---|']
        for rank,cid in enumerate(doc['top5'][axis],1):
            r=rows[cid]; title=r['title'].replace('|','／')
            lines.append(f"| {rank} | {title} | {cid} | {r['normalized_level']:g} | {r['keys']}K | {raw(r,axis)} {unit} |")
        lines += ['']
    lines+=['完整统计口径见 report.html；本结果是谱面属性试算，不改变现有 rating。']
    (output/'top5.md').write_text('\n'.join(lines),encoding='utf-8')
    bundle=output.parent/f'Malody-chart-{output.name}.zip'
    with zipfile.ZipFile(bundle,'w',zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(output.rglob('*')):
            if file.is_file(): archive.write(file,file.relative_to(output))
    print('Rendered',len(selected),'unique chart radars; 30 placements, 6 main panels, 18 key-specific panels.')
    print('Report:',output/'report.html')
    print('Bundle:',bundle, 'bytes:',bundle.stat().st_size)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument('--output',type=Path,required=True)
    run(parser.parse_args().output)
