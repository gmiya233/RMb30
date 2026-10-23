"""A single-song search returns an illustrated card; ambiguous searches stay text."""
from io import BytesIO
from pathlib import Path
import re

from PIL import Image, ImageDraw, ImageFont, ImageOps

from b30_commands import command_parts
from b30_data import identity, level
from ranking_queries import text


def artwork(rows, roots):
    seen = set()
    for row in rows:
        for digest in row.get('artworks', [])[:8]:
            if not isinstance(digest, str) or not re.fullmatch('[a-f0-9]{64}', digest) or digest in seen:
                continue
            seen.add(digest)
            for root in roots:
                source = Path(root) / (digest + '.jpg')
                try:
                    if source.is_symlink() or source.stat().st_size > 1048576:
                        continue
                    with Image.open(source) as original:
                        if original.width * original.height > 16_000_000:
                            continue
                        return ImageOps.fit(ImageOps.exif_transpose(original).convert('RGB'), (280, 280))
                except (OSError, ValueError, Image.DecompressionBombError):
                    continue
    return None


def load_rankings(queries, rows):
    boards = {}
    for chart in rows[:12]:
        try:
            ranked, stamp = queries.board(chart['cid'])
            boards[chart['cid']] = {'rows': ranked[:5], 'count': len(ranked), 'stamp': stamp}
        except (OSError, ValueError, TypeError, KeyError, AttributeError):
            boards[chart['cid']] = None
    return boards


def section_height(board):
    return 142 + 58 * max(1, len(board['rows']) if board else 0)


def render(rows, font_path, roots, boards=None):
    shown = rows[:12]
    boards = boards or {}
    width, height = 1200, 477 + sum(section_height(boards.get(row['cid'])) for row in shown)
    image = Image.new('RGB', (width, height), '#edf6f7')
    draw = ImageDraw.Draw(image)

    def label(x, y, value, size=28, color='#172b3a', limit=1050):
        value = text(value, 200)
        font = ImageFont.truetype(str(font_path), size=size)
        while value and draw.textlength(value, font=font) > limit:
            value = value[:-2] + '…' if len(value) > 2 else ''
        draw.text((x, y), value, font=font, fill=color, anchor='lt')

    draw.rounded_rectangle((28, 28, 1172, 368), radius=24, fill='white')
    cover = artwork(rows, roots)
    if cover:
        image.paste(cover, (52, 58))
    else:
        draw.rounded_rectangle((52, 58, 332, 338), radius=20, fill='#d9f1ee')
        label(116, 177, '暂无曲绘', 30, '#667e8b', 210)
    first = rows[0]
    label(364, 58, '小师妹 · 歌曲信息', 24, '#087b87', 770)
    label(364, 111, first.get('title') or '未命名', 42, limit=770)
    label(364, 190, '歌手：' + (first.get('artist') or '未知'), 28, limit=770)
    label(364, 237, 'BPM：' + str(first.get('bpm') or '未知'), 28, limit=770)
    creators = list(dict.fromkeys(str(row.get('creator') or '未知') for row in rows))
    label(364, 284, '谱师：' + ' / '.join(creators), 26, limit=770)
    label(40, 391, f'共 {len(rows)} 张谱面' + (' · 展示前12张' if len(rows) > 12 else ''), 25, '#087b87')
    y = 437
    for row in shown:
        board = boards.get(row['cid'])
        block_height = section_height(board)
        draw.rounded_rectangle((28, y, 1172, y + block_height - 16), radius=12, fill='white')
        value = level(row.get('level'), row.get('version'))
        label(48, y + 18, f'ID {row["cid"]}', 25, '#087b87', 220)
        label(274, y + 18, f'Lv.{value:g}' if value is not None else '等级待定', 25, '#aa43bf', 170)
        label(460, y + 18, row.get('version') or '未命名难度', 25, limit=690)
        if board and board['rows']:
            columns = [(48, '名次'), (132, '玩家'), (430, '成绩'), (651, '准度'), (844, '判定'), (974, 'rating')]
            for x, title in columns:
                label(x, y + 64, title, 21, '#667e8b', 180)
            for index, score in enumerate(board['rows']):
                top = y + 104 + index * 58
                judge = score.get('judge', 0)
                label(48, top, str(score['rank']), 25, '#087b87', 75)
                label(132, top, score['name'], 24, limit=278)
                label(430, top, f"{score['score']:,}", 24, limit=195)
                label(651, top, f"{score['accuracy']:.3f}%", 24, limit=175)
                label(844, top, 'ABCDE'[judge - 1] + '判' if 1 <= judge <= 5 else '?判', 24, limit=110)
                label(974, top, f"{score['rating']:.4f}", 25, '#aa43bf', 178)
            label(48, y + block_height - 43, f"前5名 · 共{board['count']}人 · {board['stamp']}", 18, '#667e8b')
        elif board is not None:
            label(48, y + 83, '暂无成绩，等待玩家上传', 25, '#667e8b')
            label(48, y + block_height - 43, board['stamp'], 18, '#667e8b')
        else:
            label(48, y + 83, '排行榜暂时不可用，请稍后再试', 25, '#667e8b')
        y += block_height
    label(40, height - 30, '完整排行：单曲榜 谱面ID [页码] · 各难度按 rating 排名', 21, '#667e8b')
    output = BytesIO()
    image.save(output, format='PNG', optimize=True)
    return output.getvalue()


class SongSearch:
    def __init__(self, queries, font, artwork_roots):
        self.queries, self.font, self.roots = queries, font, artwork_roots

    def execute(self, content):
        command, arg = command_parts(content)
        if command != '查歌' or not arg or len(content) > 200:
            return self.queries.execute(content)
        try:
            matched = self.queries.search(arg)
            songs = {identity(row) for row in matched}
            if len(songs) != 1:
                return self.queries.execute(content)
            selected = next(iter(songs))
            rows = sorted((row for row in self.queries.catalogue()[1].values()
                           if row.get('search_visible', True) is True and identity(row) == selected),
                          key=lambda row: row['cid'])
            return render(rows, self.font, self.roots, load_rankings(self.queries, rows))
        except (OSError, ValueError, TypeError, KeyError, AttributeError):
            # Metadata/image failures should not suppress useful search results.
            return self.queries.execute(content)
