"""Render an O20+N10 poster from a server-verified B30 summary."""
from functools import lru_cache
from io import BytesIO
from pathlib import Path
import re
from datetime import datetime
from zoneinfo import ZoneInfo

from PIL import Image, ImageDraw, ImageFont, ImageOps

W, H = 1600, 1760
INK, MUTED, TEAL, PURPLE = '#172b3a', '#667e8b', '#087b87', '#aa43bf'


@lru_cache(maxsize=24)
def font(path, size):
    return ImageFont.truetype(str(path), size=size)


def render(doc, font_path, artwork_root=None):
    image = Image.new('RGB', (W, H))
    # A deterministic pastel background keeps the exported image readable on phones.
    background = Image.new('RGB', (2, 2))
    background.putdata([(117, 239, 224), (195, 246, 250), (205, 235, 255), (231, 202, 252)])
    image.paste(background.resize((W, H), Image.Resampling.BILINEAR))
    draw = ImageDraw.Draw(image)

    def label(x, y, value, size=24, color=INK, width=None):
        value = re.sub(r'[\x00-\x1f\x7f]', ' ', str(value))
        f = font(font_path, size)
        if width:
            while value and draw.textlength(value, font=f) > width:
                value = value[:-2] + '…' if len(value) > 2 else ''
        draw.text((x, y), value, font=f, fill=color, anchor='lt')

    def rounded(box, color='white', radius=18):
        draw.rounded_rectangle(box, radius=radius, fill=color)

    def music_note(x, y, color):
        draw.ellipse((x, y + 23, x + 19, y + 38), fill=color)
        draw.rectangle((x + 15, y, x + 20, y + 30), fill=color)
        draw.polygon([(x + 15, y), (x + 33, y + 7), (x + 33, y + 17), (x + 15, y + 10)], fill=color)

    rounded((40, 40, 1030, 245))
    rounded((62, 60, 222, 220), '#d9f1ee', 16)
    draw.ellipse((116, 84, 170, 138), fill=TEAL)
    draw.rounded_rectangle((91, 147, 195, 200), radius=24, fill=TEAL)
    title = doc.get('player_title') or next((name for threshold, name in [(13, '龙币'), (12, '大师'), (11, '高手'), (10, '萌新')] if doc['rating'] >= threshold), '小师妹')
    rounded((246, 61, 1008, 107), '#fff4be', 8)
    label(263, 68, title, 27, width=720)
    label(246, 120, doc['name'], 44, width=750)
    label(247, 185, f"rating  {doc['rating']:.4f}", 29, TEAL)
    label(1102, 52, '节奏大师', 65, TEAL)
    label(1110, 127, 'RHYTHM MASTER', 23, TEAL)
    rounded((1070, 166, 1560, 245), '#ffffff')
    label(1103, 181, f"{'6K ' if doc.get('keys')==6 else ''}RATING   {doc['rating']:.4f}", 35, PURPLE)

    def card(row, index, x, y, color):
        rounded((x, y, x + 292, y + 186), '#ffffff', 12)
        if not row:
            rounded((x + 15, y + 22, x + 95, y + 107), '#eef6f7', 8)
            music_note(x + 40, y + 42, '#b4ccd0')
            label(x + 118, y + 35, '待收录', 25, '#94a9b1')
            label(x + 118, y + 77, f'SLOT {index:02}', 19, '#94a9b1')
            label(x + 16, y + 137, '同步成绩后收录最佳表现', 19, '#9aaeb5')
            return
        jacket = None
        digests = row.get('artworks', [row.get('artwork', '')])
        roots = [Path(artwork_root)] if artwork_root else []
        roots.append(Path('/var/lib/malody-qqbot/catalog-source/thumbs'))
        for digest in digests[:8]:
            if not isinstance(digest, str) or not re.fullmatch('[a-f0-9]{64}', digest):
                continue
            for root in roots:
                path = root / (digest + '.jpg')
                try:
                    if path.is_symlink() or path.stat().st_size > 1048576:
                        raise ValueError('invalid artwork')
                    with Image.open(path) as source:
                        if source.width * source.height > 16_000_000:
                            raise ValueError('oversize artwork')
                        jacket = ImageOps.fit(source.convert('RGB'), (86, 94))
                except (OSError, ValueError, Image.DecompressionBombError):
                    continue
                break
            if jacket is not None:
                break
        if jacket:
            image.paste(jacket, (x + 12, y + 12))
        else:
            rounded((x + 12, y + 12, x + 98, y + 106), '#d9f1ee', 7)
            music_note(x + 38, y + 35, color)
        draw.rectangle((x + 12, y + 12, x + 47, y + 35), fill=color)
        label(x + 16, y + 14, f'{index:02}', 16, 'white')
        label(x + 109, y + 14, row['title'], 23, width=172)
        level_value = row.get('level')
        level_text = f'Lv.{level_value:g}' if level_value is not None else 'Lv.?'
        sc = re.search(r'(?i)SC\s*[1-9][0-9]*', row.get('version', ''))
        if sc:
            level_text += ' · ' + sc[0].upper().replace(' ', '')
        label(x + 109, y + 49, level_text, 20, color, width=170)
        label(x + 109, y + 80, f"{row['score']:,}", 26, width=170)
        judge = row.get('judge', 0)
        label(x + 13, y + 119, 'ABCDE'[judge - 1] + '判' if 1 <= judge <= 5 else '?判', 22, color)
        label(x + 101, y + 119, f"准度 {row['accuracy']:.3f}%", 21, color, width=182)
        label(x + 13, y + 152, 'rating', 20, MUTED)
        label(x + 100, y + 148, f"{row['rating']:.4f}", 26, color)

    def section(name, subtitle, rows, count, value, y, color):
        rounded((40, y, 1560, y + 50), '#ffffff', 10)
        draw.rounded_rectangle((40, y, 49, y + 50), radius=3, fill=color)
        label(65, y + 10, name, 32, color)
        label(175, y + 15, subtitle, 23, INK)
        label(1115, y + 13, f'rating {value:.4f}', 26, color)
        label(1442, y + 15, f'{len(rows)}/{count}', 24)
        for index in range(count):
            x = 40 + (index % 5) * 307
            top = y + 66 + (index // 5) * 200
            card(rows[index] if index < len(rows) else None, index + 1, x, top, color)

    section('O20', '日常歌曲 · rating 前20', doc['old'], 20, doc['o'], 270, TEAL)
    section('N10', 'promote 新歌 · rating 前10', doc['new'], 10, doc['n'], 1150, PURPLE)
    stamp = datetime.fromtimestamp(doc['updated'] / 1000, ZoneInfo('Asia/Shanghai')).strftime('%Y-%m-%d %H:%M')
    label(42, 1674, ('6K B30 · ' if doc.get('keys')==6 else '') + 'O20 + N10 · 按歌曲收录最佳成绩 · 缺位按0计', 23, MUTED)
    label(42, 1713, '云端成绩 · ' + stamp, 21, MUTED)
    label(1350, 1710, 'Malody Link', 23, TEAL)
    output = BytesIO()
    image.save(output, format='PNG', optimize=True)
    return output.getvalue()
