"""Export the current calibrated Top 5 per axis; no rescaling or raw charts bundled."""
import argparse
import csv
from functools import lru_cache
from html import escape
import json
from pathlib import Path
import zipfile

from PIL import Image, ImageDraw, ImageFont
import render_radar_style_options as design

ROOT = Path(__file__).resolve().parents[1]
FONTS = ROOT / 'android/assets/radar-fonts'


@lru_cache(maxsize=100)
def font(size, latin=False, bold=False):
    name = ('Rajdhani-Bold.ttf' if bold else 'Rajdhani-SemiBold.ttf') if latin else 'NotoSansSC.ttf'
    result = ImageFont.truetype(str(FONTS / name), size)
    if not latin:
        result.set_variation_by_axes([600 if bold else 400])
    return result


def scene_text(self, x, y, text, size, color, align='left', bold=False, mono=False, display=False):
    text = str(text)
    latin = text.isascii()
    if y == 83:  # Fit long mixed-language song titles to the existing header.
        while size > 17 and self.draw.textlength(text, font=font(round(size * 2), latin, bold)) > 1104:
            size -= 1
    face = font(round(size * 2), latin, bold or display)
    width = self.draw.textlength(text, font=face) / 2
    left = x - width / 2 if align == 'center' else x - width if align == 'right' else x
    self.draw.text((left * 2, y * 2), text, font=face, fill=color, anchor='lt')


def scene_hell(self, x, y, width=94, height=34):
    self.rect(x, y, width, height, '#290b16', radius=1)
    self.line([(x,y),(x+width,y),(x+width,y+height),(x,y+height),(x,y)], '#9c2943', 2.5)
    face = font(52, True, True)
    left, top, right, bottom = self.draw.textbbox((0,0), 'HELL', font=face)
    center = ((x+width/2)*2, (y+height/2)*2)
    self.draw.text((center[0]-(left+right)/2, center[1]-(top+bottom)/2), 'HELL', font=face, fill='#ffe6ed')


def wrap(draw, text, face, width):
    lines, line = [], ''
    for char in text:
        if line and draw.textlength(line+char, font=face) > width:
            lines.append(line)
            line = ''
        line += char
    return lines + ([line] if line else [])


def render(dataset, metadata, output):
    output.mkdir(parents=True, exist_ok=True)
    singles = output / 'charts'
    singles.mkdir(exist_ok=True)
    names = {r['cid']: r for r in metadata}
    axes, labels = design.AXES, design.LABELS
    if dataset['axes'] != axes:
        raise ValueError('Axis order mismatch')
    design.Scene.text, design.Scene.hell = scene_text, scene_hell
    charts, tops, flat = {}, {}, []
    for axis_index, (axis, label) in enumerate(zip(axes, labels)):
        rows = sorted(dataset['charts'], key=lambda r: (-r['values'][axis_index], r['cid']))[:5]
        panel = Image.new('RGB', (2580, 1080), '#080c18')
        draw = ImageDraw.Draw(panel)
        draw.text((30,15), f'{label} TOP 5', font=font(38, False, True), fill='#f6f7fc')
        draw.text((30,70), '按当前雷达数值排序 · 10–15级 · 各图保留等级参考框 · 超框不封顶', font=font(21), fill='#a9b5ca')
        tops[axis] = []
        for rank, row in enumerate(rows, 1):
            meta = names[row['cid']]
            if meta['server_hash'] != row['server_hash']:
                raise ValueError('Chart metadata revision mismatch')
            title = meta['title']
            card_row = dict(row, title=title, **dict(zip(axes, row['values'])))
            record = {**row, 'title': title, 'creator': meta.get('creator',''), 'artist': meta.get('artist',''),
                      'version': meta.get('version',''), 'rank': rank, 'axis_value': row['values'][axis_index]}
            filename = f'{axis}-{rank}-{row["cid"]}.png'
            record['image'] = 'charts/' + filename
            if row['cid'] not in charts:
                scene = design.make('arcade', card_row, {a: 100 for a in axes})
                charts[row['cid']] = scene.image
            image = charts[row['cid']]
            image.save(singles / filename)
            thumb = image.resize((496,880), Image.Resampling.LANCZOS)
            x = 20 + (rank-1)*512
            draw.text((x+12,112), f'#{rank}  {row["values"][axis_index]:.1f}', font=font(31,True,True), fill=design.AXIS_COLORS[axis_index])
            panel.paste(thumb,(x,160))
            tops[axis].append(record)
            flat.append({'category':label, 'rank':rank,'cid':row['cid'],'title':title,'keys':row['keys'],
                         'level':row['normalized_level'],'creator':record['creator'],**dict(zip(labels,row['values']))})
        if axis == 'speed':
            note = '变速：4K/5K沿用旧标度；6K以New Astronomas为90。IMD暂按0，不代表已测得无变速。'
        elif axis == 'hold':
            note = '长条：最密连续8秒平均同时按住数；覆写者4K/5K/6K MX各为110。'
        elif axis == 'peak':
            note = '爆发：最密连续4秒的音符头＋实际时刻折点；单滑键计2折点。'
        else:
            note = '物量/爆发/滑键以事件视界各键数HD为90；HELL仅表示超过当前等级参考框。'
        draw.text((30,1042), note, font=font(19), fill='#a9b5ca')
        panel.save(output / f'top5-{axis}.png')
    manifest = {'algorithm': dataset['algorithm'], 'scale': dataset['scale'], 'charts_in_sample':len(dataset['charts']),
                'ranking':'current_calibrated_axis_value_desc_then_cid', 'top5': tops}
    (output/'top5.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    with (output/'top5.csv').open('w',encoding='utf-8-sig',newline='') as stream:
        writer=csv.DictWriter(stream,fieldnames=list(flat[0]));writer.writeheader();writer.writerows(flat)
    overview = Image.new('RGB',(1548,100+648*6),'#080c18')
    draw = ImageDraw.Draw(overview)
    draw.text((22,12),'六维图 · 各类型 TOP 5',font=font(34,False,True),fill='#f6f7fc')
    draw.text((22,61),'最新校准试算 · 点开分类原图查看数值与完整卡片',font=font(19),fill='#a9b5ca')
    for index, axis in enumerate(axes):
        with Image.open(output/f'top5-{axis}.png') as panel:
            overview.paste(panel.resize((1548,648),Image.Resampling.LANCZOS),(0,100+index*648))
    overview.save(output/'overview.png')
    page=['<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>最新六维各类型TOP5</title><style>body{margin:0;background:#080c18;color:#f6f7fc;font:16px/1.6 system-ui,"Microsoft YaHei",sans-serif}main{max-width:1600px;margin:auto;padding:24px}a{color:#f7a8c4}nav{display:flex;gap:24px;flex-wrap:wrap}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:16px}article{background:#111729;padding:12px;border-radius:12px}img{width:100%;height:auto}h3{font-size:16px}p{color:#a9b5ca}.scores{font-size:13px}</style><main><h1>六维图 · 各类型 TOP 5</h1><p>按最新雷达数值降序，CID打破同分；313张10–15级样本，全键数混合。每张图采用自身等级参考框，数字不封顶。变速4K/5K尚沿用旧标度，6K以新天文为90，跨键数比较暂作试算。IMD变速按约定显示0。</p><nav>']
    page += [f'<a href="#{axis}">{escape(label)}</a>' for axis,label in zip(axes,labels)]
    page += ['<a href="top5.csv">下载表格CSV</a></nav>']
    for axis,label in zip(axes,labels):
        page += [f'<section id="{axis}"><h2>{escape(label)} TOP 5</h2><a href="top5-{axis}.png">打开本组汇总原图</a><div class="cards">']
        for row in tops[axis]:
            page += [f'<article><h3>#{row["rank"]} {escape(row["title"])}</h3><p>{row["keys"]}K · Lv.{row["normalized_level"]:g} · CID {row["cid"]}<br>{escape(row["version"])}<br>谱师：{escape(row["creator"])}</p><a href="{row["image"]}"><img loading="lazy" src="{row["image"]}" alt="{escape(row["title"],quote=True)}六维图"></a><p class="scores">'+' · '.join(f'{escape(name)} {value:.1f}' for name,value in zip(labels,row['values']))+'</p></article>']
        page += ['</div></section>']
    page += ['</main></html>']
    (output/'index.html').write_text(''.join(page),encoding='utf-8')
    archive_path = output.parent/'Malody-radar-top5-calibrated.zip'
    with zipfile.ZipFile(archive_path,'w',zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(output.rglob('*')):
            if path.is_file(): archive.write(path, path.relative_to(output))
    print(json.dumps({'category_panels':6,'single_cards':30,'distinct_charts':len(charts),'output':str(output),'zip':str(archive_path)},ensure_ascii=False))


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--metadata',type=Path,default=ROOT/'build/radar-calibration/current-chart-metadata.json')
    parser.add_argument('--output',type=Path,default=ROOT/'dist/radar-top5-calibrated')
    args=parser.parse_args()
    render(json.loads((ROOT/'android/assets/chart-radar.json').read_text(encoding='utf-8')),json.loads(args.metadata.read_text(encoding='utf-8')),args.output)
