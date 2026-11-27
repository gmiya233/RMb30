"""Arcade-style song information for search and recommendations."""
import os
from collections import defaultdict
from functools import lru_cache
from io import BytesIO
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps

from b30_data import level
from player_avatar import load as load_player_avatar
from radar_image import RadarPalette, draw_radar
from ranking_queries import text


BACKGROUND = '#0D1719'
INK, MUTED, ACCENT, PANEL, RULE = '#F2FAEF', '#ADC8BD', '#D7FA7C', '#172729', '#385452'
PALETTE = RadarPalette(INK, MUTED, RULE, ACCENT, '#101C1D', (198, 242, 100, 95),
                       '#FFE39B', ('#FF9CD5', '#D8F58A', '#FFD470', '#9AC8FF', '#C7B6FF', '#64FDEF'))
TIERS = {
    1: ('hard',),
    2: ('normal', 'hard'),
    3: ('easy', 'normal', 'hard'),
    4: ('easy', 'normal', 'hard', 'master'),
}
FONTS = {
    'cn': 'SystemFont1.ttf',
    'num': 'BarlowSemiCondensed-Bold.ttf',
    'regular': 'BarlowSemiCondensed-Regular.ttf',
    'brand': 'suyuanxieti.ttf',
}


def asset_root():
    return Path(os.getenv('MALODY_SONG_CARD_ASSETS', str(Path(__file__).with_name('song-card-assets'))))


def assets_ready():
    root = asset_root()
    return (
        all((root / 'fonts' / name).is_file() for name in FONTS.values())
        and all((root / f'rm-difficulty-{tier}.png').is_file() for tier in TIERS[4])
        and all((root / f'rm-key-{key}.png').is_file() for key in (4, 5, 6))
        and (root / 'axis-slide-reference.png').is_file()
    )


@lru_cache(maxsize=48)
def font(size, kind):
    return ImageFont.truetype(str(asset_root() / 'fonts' / FONTS[kind]), size)


@lru_cache(maxsize=32)
def sprite(path, size):
    with Image.open(path) as source:
        return ImageOps.contain(source.convert('RGBA'), size, method=Image.Resampling.LANCZOS)


@lru_cache
def axis_icon(index, color):
    if index==5:
        return sprite(asset_root() / 'axis-slide-reference.png', (26, 26))

    tile=Image.new('RGBA',(104,104))
    draw=ImageDraw.Draw(tile)

    def pill(box, radius=6):
        draw.rounded_rectangle(tuple(4*v for v in box),radius=radius,fill=color)

    def dot(x,y,r):
        draw.ellipse(tuple(4*v for v in (x-r,y-r,x+r,y+r)),fill=color)

    def shape(points):
        draw.polygon([(4*x,4*y) for x,y in points],fill=color)

    if index==0:  # Cascade of three key strikes.
        pill((13,3,23,8))
        pill((8,10,21,15))
        pill((3,17,18,22))
    elif index==1:  # Three keys joined by one beam.
        pill((5,5,23,8))
        for x in (5,13,21):
            pill((x,7,x+2,18),4)
            dot(x-1,18,3.2)
    elif index==2:  # Comet and impact spark.
        shape(((17,1),(19,7),(25,9),(19,12),(16,20),(14,13),(8,10),(14,7)))
        pill((2,14,12,17))
        pill((5,20,12,23))
    elif index==3:  # Accelerating needle.
        draw.arc((24,20,92,88),178,358,fill=color,width=11)
        shape(((13,16),(22,7),(18,18)))
        dot(15,17,2.6)
        pill((2,19,8,22))
        pill((1,13,5,15))
    else:  # Hold head and sustained tail.
        pill((9,5,17,24),12)
        pill((5,2,21,10),12)
        draw.ellipse((42,14,62,30),fill=(0,0,0,0))
        draw.rounded_rectangle((48,48,56,79),radius=4,fill=(0,0,0,0))
        tile=tile.transpose(Image.Transpose.ROTATE_180)
    return tile.resize((26,26),Image.Resampling.LANCZOS)


def draw_axis_icon(image, index, center, color):
    icon=axis_icon(index,color)
    x,y=center
    image.paste(icon,(x-icon.width//2,y-icon.height//2),icon)


@lru_cache
def difficulty_badge(difficulty, level, width):
    with Image.open(asset_root() / f'rm-difficulty-{difficulty}.png') as image:
        plate=image.convert('RGBA')
    face=font(31,'cn')
    caption = f'{level:g}级' if level is not None else '待定'
    draw=ImageDraw.Draw(plate)
    left,top,right,bottom=draw.textbbox((0,0),caption,font=face)
    # Match the ink center of the original difficulty lettering (y=36).
    draw.text((158-(left+right)/2,36-(top+bottom)/2),caption,font=face,fill='#FFFDFD')
    return plate.resize((width,round(width*plate.height/plate.width)),Image.Resampling.LANCZOS)



def chart_keys(row):
    return (row.get('radar') or {}).get('keys') or row.get('keys')


def chart_styles(rows):
    groups = defaultdict(list)
    for row in rows:
        grade = level(row.get('level'), row.get('version'))
        mapper = str(row.get('creator') or '').strip()
        author = mapper if mapper not in ('', '-', '未知', '未提供') else row['cid']
        groups[chart_keys(row), author].append((grade, row['cid']))

    result = {}
    for charts in groups.values():
        charts.sort(key=lambda chart: (chart[0] is None, chart[0], chart[1]))
        tiers = TIERS[min(len(charts), 4)]
        for index, (grade, cid) in enumerate(charts):
            result[cid] = (grade, tiers[min(index, len(tiers)-1)])
    return result


def load_board_avatars(queries, boards):
    rows = (score for board in boards.values() if board for score in board['rows'])
    return load_avatars(queries, rows)


def load_avatars(queries, rows, *, size=34):
    players = {str(score.get('qq') or '') for score in rows} - {''}
    if not players:
        return {}
    root = os.getenv('MALODY_QQ_PROFILES', str(queries.root.parent.parent))
    server, _ = queries.catalogue()
    avatars = {}
    for qq in players:
        try:
            data = load_player_avatar(root, qq, server)
            if data:
                with Image.open(BytesIO(data)) as original:
                    if original.width * original.height <= 4_000_000:
                        source = ImageOps.exif_transpose(original).convert('RGB')
                        avatars[qq] = ImageOps.fit(source, (size, size), method=Image.Resampling.LANCZOS)
        except (OSError, ValueError, TypeError, AttributeError, Image.DecompressionBombError):
            continue
    return avatars


class Card:
    def __init__(self, height):
        self.image = Image.new('RGB', (1000, height), BACKGROUND)
        self.draw = ImageDraw.Draw(self.image)

    def text(self, pos, value, size, color, kind='cn', anchor='lt', limit=None):
        value = text(value, 240)
        if kind in ('num', 'regular') and any(ord(char) > 0x024f for char in value):
            kind = 'cn'
        face = font(size, kind)
        if limit and self.draw.textlength(value, font=face) > limit:
            while value and self.draw.textlength(value + '…', font=face) > limit:
                value = value[:-1]
            value += '…'
        self.draw.text(pos, value, font=face, fill=color, anchor=anchor)

    def title(self, value, box, *, max_size=60, min_size=36, line_limit=2):
        value = text(value, 240).strip() or '未命名'
        x, top, right, bottom = box
        width = right-x
        kind = 'cn' if any(ord(char) > 0x024f for char in value) else 'num'

        def bounds(line, face):
            return self.draw.textbbox((0, 0), line, font=face, anchor='lt')

        def fits(line, face):
            left, _, right, _ = bounds(line, face)
            return right-left <= width

        def draw_lines(lines, face):
            boxes = [bounds(line, face) for line in lines]
            heights = [lower-upper for _, upper, _, lower in boxes]
            gap = 6
            height = sum(heights) + gap*(len(lines)-1)
            if height > bottom-top:
                return False
            y = top + (bottom-top-height)//2
            for line, (left, upper, _, _), line_height in zip(lines, boxes, heights):
                self.draw.text((x-left, y-upper), line, font=face, fill=INK, anchor='lt')
                y += line_height + gap
            return True

        for size in range(max_size, min_size-1, -1):
            face = font(size, kind)
            if fits(value, face) and draw_lines([value], face):
                return

        for size in range(min_size, 23, -1):
            face = font(size, kind)
            split = len(value)
            while split > 1 and not fits(value[:split], face):
                split -= 1
            if split == len(value):
                if draw_lines([value], face):
                    return
            elif line_limit > 1:
                word_end = value.rfind(' ', max(0, split-12), split+1)
                for end in dict.fromkeys((word_end, split)):
                    if end > 0 and fits(value[end:].lstrip(), face):
                        if draw_lines([value[:end].rstrip(), value[end:].lstrip()], face):
                            return

        face = font(23, kind)
        first = value
        lines = []
        if line_limit > 1:
            end = len(value)
            while end > 1 and not fits(value[:end], face):
                end -= 1
            lines.append(value[:end].rstrip())
            first = value[end:].lstrip()
        if not fits(first, face):
            while first and not fits(first.rstrip()+'…', face):
                first = first[:-1]
            first = first.rstrip()+'…'
        if first:
            lines.append(first)
        draw_lines(lines, face)

    def line(self, ends):
        self.draw.line(ends, fill=RULE, width=1)

    def art(self, source, x, y, size, radius):
        with ImageOps.fit(source, (size, size), method=Image.Resampling.LANCZOS) as cover:
            mask = Image.new('L', (size, size))
            ImageDraw.Draw(mask).rounded_rectangle((0, 0, size-1, size-1), radius, fill=255)
            self.image.paste(cover, (x, y), mask)

    def badges(self, keys, tier, grade, y, *, x=61):
        if keys in (4, 5, 6):
            badge = sprite(asset_root() / f'rm-key-{keys}.png', (100, 46))
            self.image.paste(badge, (x, y+(46-badge.height)//2), badge)
        else:
            self.text((x+43, y+23), f'{keys}K' if keys else '键数待定', 24, ACCENT, 'num', 'mm')
        badge = difficulty_badge(tier, grade, 146)
        self.image.paste(badge, (x+109, y+(46-badge.height)//2), badge)

    def radar(self, row, grade, y):
        with Image.new('RGB', (600, 600), PANEL) as tile:
            draw_radar(tile, (0, 0), row, asset_root() / 'fonts' / FONTS['cn'], grade,
                       palette=PALETTE, center_hell=True, show_reference=False, axis_icon=draw_axis_icon)
            self.image.paste(tile.resize((445, 445), Image.Resampling.LANCZOS), (62, y))

    def avatar(self, image, name, x, y, *, size=34):
        if image:
            self.art(image, x, y, size, size//2)
            return
        colors = ('#80558F', '#456E9C', '#5A7962', '#916A48', '#3C797A', '#8F566A')
        initial = (name.strip()[:1] or '?').upper()[:1]
        self.draw.ellipse((x, y, x+size-1, y+size-1), fill=colors[ord(initial) % len(colors)],
                          outline='#B0BFC1')
        self.text((x+size//2, y+size//2), initial, round(size*19/34), '#FFFFFF', 'num', 'mm')

    def board(self, board, y):
        x, right = 557, 940
        t = self.text
        t((x, y), '单曲榜 · TOP 5', 22, MUTED)
        t((right, y), '准度', 20, MUTED, anchor='rt')
        if board is None:
            t((x, y+71), '排行榜暂时不可用，请稍后再试', 21, MUTED)
            return
        if not board['rows']:
            t((x, y+70), '暂无成绩，等待玩家上传', 22, MUTED)
            t((x, y+110), board['stamp'], 17, MUTED, limit=380)
            return
        avatars = board.get('avatars', {})
        for index, score in enumerate(board['rows']):
            top = y+34+index*57
            name = text(score.get('name') or '未知玩家', 60)
            self.line((x, top, right, top))
            t((x, top+12), f"{score['rank']:02d}", 23, ACCENT if score['rank'] == 1 else MUTED, 'num')
            self.avatar(avatars.get(str(score.get('qq') or '')), name, x+45, top+9)
            t((x+88, top+7), name, 23, INK, limit=205)
            t((right, top+7), f"{score['accuracy']:.3f}%", 23, INK, 'num', 'rt')
            judge = score.get('judge', 0)
            judge_label = 'ABCDE'[judge-1] if 1 <= judge <= 5 else '?'
            t((x+88, top+33), f"{score['score']:,}  ·  {judge_label}判", 17, MUTED, limit=175)
            t((right, top+32), f"rating {score['rating']:.4f}", 17, ACCENT, 'regular', 'rt')
        self.line((x, y+319, right, y+319))
        t((x, y+330), f"共 {board['count']} 人", 16, MUTED)
        t((right, y+330), board['stamp'], 16, MUTED, anchor='rt', limit=270)


def render_card(rows, cover, boards, heading):
    from song_aliases import append_aliases

    shown = rows[:12]
    styles = chart_styles(rows)
    first = rows[0]
    width, top, step = 1000, 230, 533
    end = top + len(shown)*step
    card = Card(end+30)
    t = card.text
    t((38, 25), heading.replace(' · ', ' / '), 25, INK, 'brand', limit=924)
    card.line((38, 65, 962, 65))
    if cover:
        card.art(cover, 38, 84, 130, 8)
    else:
        card.draw.rounded_rectangle((38, 84, 168, 214), radius=8, fill=PANEL)
        t((103, 149), '暂无曲绘', 19, MUTED, anchor='mm')
    card.title(first.get('title') or '未命名', (192, 82, 962, 163))
    t((194, 172), first.get('artist') or '未知歌手', 28, MUTED, 'regular', limit=460)
    bpm = text(first.get('bpm') or '未知', 44)
    t((962, 174), f'BPM {bpm}  /  {len(rows)} 张谱面', 25, ACCENT, anchor='rt', limit=288)
    for index, row in enumerate(shown):
        y = top + index*step
        cid = row['cid']
        grade, tier = styles[cid]
        card.draw.rounded_rectangle((36, y, 964, y+512), radius=15, fill=PANEL)
        card.badges(chart_keys(row), tier, grade, y+8)
        t((330, y+18), f"谱师  {row.get('creator') or '未知'}", 24, INK, limit=455)
        t((940, y+19), f'CID {cid}', 26, MUTED, 'num', 'rt')
        card.line((62, y+61, 940, y+61))
        card.radar(row, grade, y+62)
        card.line((529, y+79, 529, y+443))
        card.board(boards.get(cid), y+87)
    footer = '完整排行：单曲榜 谱面ID [页码] · 按 rating 排名'
    if len(rows) > len(shown):
        footer += ' · 展示前 12 张谱面'
    t((38, end-1), footer, 19, MUTED, limit=width-76)
    output = BytesIO()
    card.image.save(output, format='PNG')
    return append_aliases(output.getvalue(), rows, asset_root() / 'fonts' / FONTS['cn'])
