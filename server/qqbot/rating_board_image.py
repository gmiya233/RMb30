"""Arcade-style leaderboard images for QQ group replies."""
from io import BytesIO

from song_card_art import (
    ACCENT, INK, MUTED, PANEL, Card, assets_ready, chart_keys, chart_styles, load_avatars,
)


MEDALS = ('#EFCE7A', '#C8D6E3', '#DDA782')
AVATAR_SIZE = 56


def draw_medal(card, rank, x, y):
    color = MEDALS[rank-1]
    card.draw.polygon([(x-15, y+9), (x-6, y+21), (x, y+17),
                       (x+6, y+21), (x+15, y+9), (x, y+6)], fill=color)
    card.draw.ellipse((x-20, y-21, x+20, y+16), fill=color)
    card.draw.ellipse((x-16, y-17, x+16, y+12), outline='#34403B')
    card.text((x, y-2), str(rank), 24, '#172521', 'num', 'mm')


def draw_chart(card, chart, charts, cover):
    if cover:
        card.art(cover, 38, 180, 90, 8)
    else:
        card.draw.rounded_rectangle((38, 180, 128, 270), radius=8, fill=PANEL)
        card.text((83, 225), '暂无曲绘', 17, MUTED, anchor='mm')
    card.title(chart.get('title') or '未命名', (147, 181, 787, 225),
               max_size=38, min_size=27, line_limit=1)
    level, tier = chart_styles(charts)[chart['cid']]
    card.badges(chart_keys(chart), tier, level, 228, x=148)
    card.text((960, 207), f"CID {chart['cid']}", 25, ACCENT, 'num', 'rm')
    card.text((960, 248), '谱师  '+str(chart.get('creator') or '未知'), 22, MUTED,
              anchor='rm', limit=530)


def draw_rows(rows, page, stamp, avatars, *, title='总战力榜', six=False, activity=False,
              chart=None, charts=(), cover=None):
    first_y, row_height = (354 if chart else 252), 87
    bottom = first_y + row_height * max(len(rows), 1) + 12
    card = Card(bottom + 56)
    draw = card.draw
    t = card.text
    t((38, 25), '小师妹 / 排行榜', 25, INK, 'brand')
    card.line((38, 65, 962, 65))
    t((38, 107 if six else 126), title, 58, INK, anchor='lm')
    t((960, 139), page, 25, ACCENT, anchor='rm', limit=470)
    if six:
        t((41, 158), 'O20 + N10 · 仅统计 6K 谱面', 23, MUTED, 'regular', 'lm')
    if chart:
        draw_chart(card, chart, charts, cover)

    draw.rounded_rectangle((36, first_y-60, 964, bottom), radius=15, fill=PANEL)
    header = first_y-26
    t((104, header), '排名', 22, MUTED, anchor='mm')
    t((275, header), '玩家', 22, MUTED, anchor='lm')
    t((924, header), '游玩次数' if activity else 'RT', 26, ACCENT, 'num', 'rm')
    if chart:
        t((744, header), '准度', 22, MUTED, anchor='rm')
    card.line((62, first_y-1, 938, first_y-1))
    for index, row in enumerate(rows):
        rank = row['rank']
        start = first_y + index * row_height
        center = start + 43
        if rank == 1:
            draw.rounded_rectangle((51, start+3, 948, start+83), radius=10,
                                   fill='#23362D', outline='#496142')
        if 1 <= rank <= len(MEDALS):
            draw_medal(card, rank, 104, center)
        else:
            t((104, center), f'{rank:02d}', 31, MUTED, 'num', 'mm')
        card.avatar(avatars.get(row['qq']), row['name'], 196, center-AVATAR_SIZE//2, size=AVATAR_SIZE)
        t((276, center-12 if six or chart else center), row['name'], 33, INK,
          'num', 'lm', limit=325 if chart or activity else 455)
        if six:
            t((276, center+19), f"O20  {row['o']:.4f}   ·   N10  {row['n']:.4f}", 22, MUTED, 'regular', 'lm')
        if chart:
            judge = 'ABCDE'[row['judge']-1]+'判' if row['judge'] else '未知判定'
            t((276, center+19), f"{row['score']:,} 分  ·  {judge}", 22, MUTED, 'regular', 'lm', limit=325)
            t((744, center), f"{row['accuracy']:.3f}%", 28, INK, 'regular', 'rm')
        value = f"{row['count']:,}" if activity else f"{row['rating']:.4f}"
        t((924, center), value, 38, ACCENT if rank == 1 else INK, 'num', 'rm')
        if rank != 1 and index+1 < len(rows):
            draw.line((65, start+row_height, 935, start+row_height), fill='#2D4745')
    if not rows:
        t((500, first_y+43), '暂无游玩记录' if activity else '暂无成绩', 27, MUTED, anchor='mm')
    t((38, bottom+29), stamp, 18, MUTED, anchor='lm', limit=924)
    output = BytesIO()
    card.image.save(output, format='PNG', optimize=True)
    return output.getvalue()


def render(queries, rows, page, stamp, *, title='总战力榜', six=False, activity=False, chart=None):
    if not assets_ready():
        return None
    try:
        avatars = load_avatars(queries, rows, size=AVATAR_SIZE)
    except (OSError, ValueError, TypeError, AttributeError):
        avatars = {}
    cover, charts = None, ()
    if chart:
        from song_search import artwork, song_key
        key = song_key(chart)
        charts = [row for row in queries.catalogue()[1].values()
                  if row['cid'] == chart['cid'] or
                  (row.get('search_visible', True) is True and song_key(row) == key)]
        roots = getattr(queries, 'ranking_artwork', (queries.catalogue_file.parent / 'thumbs',))
        cover = artwork([chart, *(row for row in charts if row['cid'] != chart['cid'])], roots)
    try:
        data = draw_rows(rows, page, stamp, avatars, title=title, six=six, activity=activity,
                         chart=chart, charts=charts, cover=cover)
    finally:
        if cover:
            cover.close()
    return data if len(data) <= 4 * 1024 * 1024 else None
