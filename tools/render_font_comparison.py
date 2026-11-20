"""Render font-only comparisons using the existing measured radar scene."""
from pathlib import Path
import json
import urllib.request
from html import escape
from PIL import Image, ImageDraw, ImageFont
import render_radar_style_options as design

ROOT = design.ROOT
OUT = ROOT / 'dist/font-comparison-2026-10-30'
FONTS = ROOT / 'assets/fonts/radar-preview'
OUT.mkdir(parents=True, exist_ok=True)
FONTS.mkdir(parents=True, exist_ok=True)
sources = {
    'NotoSansSC.ttf': 'notosanssc/NotoSansSC%5Bwght%5D.ttf',
    'BarlowSemiCondensed-SemiBold.ttf': 'barlowsemicondensed/BarlowSemiCondensed-SemiBold.ttf',
    'BarlowSemiCondensed-Bold.ttf': 'barlowsemicondensed/BarlowSemiCondensed-Bold.ttf',
    'Rajdhani-SemiBold.ttf': 'rajdhani/Rajdhani-SemiBold.ttf',
    'Rajdhani-Bold.ttf': 'rajdhani/Rajdhani-Bold.ttf',
}
for filename, remote in sources.items():
    target = FONTS / filename
    if not target.exists():
        urllib.request.urlretrieve('https://raw.githubusercontent.com/google/fonts/main/ofl/' + remote, target)

theme = 'A'
def font_for(text, size, bold=False, display=False, mono=False):
    latin = all(ord(c) < 128 for c in str(text))
    weight = 600 if bold else 400
    filename = 'NotoSansSC.ttf'
    if latin and theme != 'C':
        family = 'BarlowSemiCondensed' if theme == 'A' else 'Rajdhani'
        filename = family + ('-Bold.ttf' if display else '-SemiBold.ttf')
    font = ImageFont.truetype(str(FONTS / filename), round(size * 2))
    if filename == 'NotoSansSC.ttf':
        font.set_variation_by_axes([weight])
    return font

def text(self, x, y, value, size, color, align='left', bold=False, mono=False, display=False):
    # Use a consistent Chinese family for mixed-language song names.
    value = str(value)
    if size == 32:
        bold = False
    if size == 22:
        size = 21
    font = font_for(value, size, bold, display, mono)
    width = self.draw.textlength(value, font=font) / 2
    left = x - width / 2 if align == 'center' else x - width if align == 'right' else x
    self.draw.text((left * 2, y * 2), value, font=font, fill=color, anchor='lt')

design.Scene.text = text
data = json.loads((design.SOURCE / 'analysis.json').read_text(encoding='utf-8'))
refs = json.loads((design.SOURCE / 'overflow-candidates.json').read_text(encoding='utf-8'))['references']
row = next(r for r in data['charts'] if r['cid'] == 600214)
names = [('A', '清爽竞技', '思源黑体系 + Barlow Semi Condensed'),
         ('B', '科技仪表', '思源黑体系 + Rajdhani'),
         ('C', '极简统一', 'Noto Sans SC / 思源黑体系')]
board = Image.new('RGB', (1940, 1250), '#e9edf3')
draw = ImageDraw.Draw(board)
for index, (key, label, subtitle) in enumerate(names):
    theme = key
    scene = design.make('arcade', row, refs[str(row['keys'])])
    scene.image.save(OUT / f'font-{key}.png')
    x = 20 + index * 640
    titlefont = font_for(label, 16, True)
    smallfont = font_for(subtitle, 9)
    draw.text((x + 8, 20), key + '  ' + label, font=titlefont, fill='#17283e')
    draw.text((x + 8, 65), subtitle, font=smallfont, fill='#53647b')
    board.paste(scene.image.resize((620, 1100), Image.Resampling.LANCZOS), (x, 120))
board.save(OUT / 'comparison.png')
(OUT / 'README.md').write_text('字体对比预览：沿用现有雷达数据、颜色、布局。A 为 Noto Sans SC + Barlow Semi Condensed；B 为 Noto Sans SC + Rajdhani；C 为统一 Noto Sans SC。仅预览，未修改 App。字体来源：https://github.com/google/fonts/tree/main/ofl\n', encoding='utf-8')
print(OUT / 'comparison.png')
