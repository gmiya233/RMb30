"""Public radar rules, sharing the exact anchors used by catalogue publication."""
import json
from pathlib import Path


def explanation():
    rules = json.loads(Path(__file__).with_name('radar-rules.json').read_text(encoding='utf-8'))
    notes = rules['references']['notes']
    slide = rules['references']['slide']
    lines = [
        '谱面六维 · 当前算法与参考',
        '各轴＝原始指标÷该轴100分基准×100，不封顶；这不是玩家rating。',
        f"① 物量：可玩音符头总数。{notes['title']} 4K HD（ID {notes['cid']}）{notes['raw']}个＝100，4/5/6K共用。长条尾、折点不重复计物量。",
        '② 多押：三押组数＋四押组数；暂保留原样本刻度，无单曲固定参考。',
        '③ 爆发：最密连续4秒内（音符头＋滑键折点）÷4；《事件视界》同键数HD＝90，4/5/6K分别25.5／29.75／27.25点每秒。',
        '④ 长按复合：最密连续8秒内直长条占用时长之和÷8；《覆写者》同键数MX＝110，原始强度分别2.462375／3.000625／3.9835平均条。横向滑键不算直长条。',
        f"⑤ 滑键：N^0.7 × (N/T)^0.2 × D^0.1，再除以两首参考强度均值{slide['raw']:.6f}×100。N为全曲滑键总折点数，T为首个音符至最后音符结束的真实秒数，D为最密真实4秒折点密度。总量70%、全曲实际密度20%、局部峰值10%，为乘法指数权重。风纪委员4K HD（ID 604398）：3049点／332.958秒、峰值20.75；GOODRAGE 4K HD（ID 604401）：1059点／117.026秒、峰值24。两首均值＝100，4/5/6K共用。单滑计2点，连续滑计转角＋末端，共线冗余点不计。",
        '⑥ 变速：有效BPM／hs／scroll变化次数÷有效分钟数，同刻变化合并；New Astronomas 6K＝90（77次／121.5秒）；4/5K保留原样本刻度。IMD暂不统计，显示0。',
        '真实时间：按每段BPM积分，Δ秒＝Δ拍×60/BPM；折点在实际发生时计入，不把整条折线提前算在起点。BPM已进入秒数，不再额外乘BPM。scroll只改变视觉滚速，不改变打击时刻。',
        '窗口为连续半开区间[start,start+4)，恰好相隔4秒不挤在同窗；无滑键为0。',
        '等级框：12级80、13级90、14级100、15级110；HELL表示超过对应等级框，不裁掉原值。',
        '100分基准（4K／5K／6K）：',
    ]
    for axis, label in [('notes','物量'),('chord','多押'),('peak','爆发'),('hold','长按'),('slide','滑键'),('speed','变速')]:
        lines.append(label+'：'+'／'.join(f"{rules['anchors'][str(k)][axis]:.6g}" for k in (4,5,6)))
    lines.append('算法版本：'+rules['algorithm'])
    return '\n'.join(lines)
