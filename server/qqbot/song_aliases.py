"""Persistent cross-group song aliases; shared by all difficulties of a SID."""
import sqlite3
import unicodedata
from pathlib import Path
from contextlib import closing
from b30_data import identity


def normalized(value):
    return unicodedata.normalize('NFKC', value).casefold().strip()


class SongAliases:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('CREATE TABLE IF NOT EXISTS song_aliases (song TEXT NOT NULL, normalized TEXT NOT NULL, alias TEXT NOT NULL, PRIMARY KEY(song,normalized))')
        self.path.chmod(0o600)

    @staticmethod
    def key(chart):
        kind, value = identity(chart)
        return f'{kind}:{value}'

    def all(self):
        result = {}
        with closing(sqlite3.connect(self.path)) as db:
            for key, alias in db.execute('SELECT song,alias FROM song_aliases ORDER BY rowid'):
                result.setdefault(key, []).append(alias)
        return result

    def add(self, chart, alias):
        alias = unicodedata.normalize('NFKC', alias).strip()
        if not 1 <= len(alias) <= 48 or any(unicodedata.category(c).startswith('C') for c in alias):
            raise ValueError('别名需为1～48个字，不能包含换行或控制字符。')
        if any(c in alias for c in '<>@'):
            raise ValueError('别名不能包含 @、<、>。')
        key, norm = self.key(chart), normalized(alias)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('BEGIN IMMEDIATE')
            if db.execute('SELECT 1 FROM song_aliases WHERE song=? AND normalized=?', (key,norm)).fetchone():
                return False
            if db.execute('SELECT count(*) FROM song_aliases WHERE song=?', (key,)).fetchone()[0] >= 40:
                raise ValueError('这首歌已达到40个别名的上限。')
            db.execute('INSERT INTO song_aliases VALUES (?,?,?)', (key,norm,alias))
        return True


def append_aliases(data, rows, font_path):
    from io import BytesIO
    from PIL import Image, ImageDraw, ImageFont
    aliases = list(dict.fromkeys(a for row in rows for a in row.get('aliases', [])))
    with Image.open(BytesIO(data)) as source:
        source = source.convert('RGB')
        font = ImageFont.truetype(str(font_path), 25)
        probe = ImageDraw.Draw(source)
        lines, line = [], ''
        for char in '歌曲别名：' + (' / '.join(aliases) if aliases else '暂无，可点击「添加别名」设置'):
            if probe.textlength(line + char, font=font) > source.width - 90:
                lines.append(line); line = ''
            line += char
        if line: lines.append(line)
        image = Image.new('RGB', (source.width, source.height + 40 + 36*len(lines)), '#101827')
        image.paste(source, (0,0)); draw = ImageDraw.Draw(image)
        for i, line in enumerate(lines):
            draw.text((40,source.height+16+i*36), line, font=font, fill='#E2EBF2')
        output = BytesIO(); image.save(output, format='PNG', optimize=True)
        if output.tell() > 3500*1024:
            output = BytesIO(); image.save(output, format='JPEG', quality=90, optimize=True)
        return output.getvalue()
