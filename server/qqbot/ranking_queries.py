"""Read-only queries over the existing Java-generated v2 leaderboard snapshots."""
import json
import math
from pathlib import Path
import re
import time
import unicodedata
from datetime import datetime
from zoneinfo import ZoneInfo


class Unavailable(ValueError):
    pass


def read_json(path, limit=16 * 1024 * 1024):
    path = Path(path)
    if path.is_symlink() or path.stat().st_size > limit:
        raise Unavailable('invalid snapshot')
    with path.open('rb') as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise Unavailable('oversize snapshot')
    return json.loads(data)


def text(value, maximum=60):
    value = re.sub(r'[\x00-\x1f\x7f]', ' ', str(value))
    return value.translate(str.maketrans({'@': '＠', '<': '〈', '>': '〉'}))[:maximum]


def normalize(value):
    return unicodedata.normalize('NFKC', str(value)).casefold().strip()


def number(value, lower=0, upper=1000000000000):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or not lower <= value <= upper:
        raise Unavailable('invalid numeric field')
    return value


class Queries:
    HELP = ('小师妹 · 排行查询\n'
            '绑定 QQ号：设置本群默认查询QQ\n'
            'b30：返回已绑定QQ的O20+N10图片\n'
            'rating：查看 rating 计算规则\n'
            '我的绑定 / 解绑：查看或解除绑定\n'
            '总榜 [页码]：综合 rating，每页10人\n'
            '查排名 QQ号：查询玩家排名\n'
            '查歌 歌名：搜索歌曲和谱面ID\n'
            '谱面预览 谱面ID [页码]：EX直轨展开图\n'
            '单曲榜 谱面ID [页码]：单谱排行\n'
            '单曲榜 歌名：先搜索，再选择谱面ID\n'
            '例：单曲榜 604342\n'
            '请在群内 @我 后发送命令。')

    RATING_HELP = (
        '小师妹来讲讲 rating 怎么算～\n'
        '这是本服的换算 rating，和 Malody 官方 MM 不同。\n\n'
        '① 单曲 rating = 定级 × 准度系数 × 判定倍率\n'
        '判定倍率：A判 0.78，B判 0.85，C判 1.00，D判 1.10，E判 1.20。\n'
        '设准度为 P（例如 99.6%，P 就填 99.6）：\n'
        'P < 99.5：系数 = (P ÷ 99.5)²\n'
        '99.5 ≤ P < 99.8：系数 = 1 + (P − 99.5) ÷ 30\n'
        'P ≥ 99.8：系数固定为 1.01。\n'
        '例如：13级、C判、99.5% = 13.0000；99.8%及以上 = 13.1300。\n\n'
        '② 综合 rating =（O20 的单曲 rating 总和 + N10 的单曲 rating 总和）÷ 30\n'
        'O20：普通歌曲中最高的20首；N10：服务器标为新歌（promote）的歌曲中最高的10首。\n'
        '同一首歌的不同难度只取最高的一条；同歌只要有谱面属于新歌，就归入 N10，不重复进 O20。'
        '不足20首或10首的空位按0算，所以先打满两栏也能提升总 rating 哦！\n\n'
        '③ 定级口径\n'
        '普通谱面：原等级低于16时直接使用；16→8，17–18→9，19–20→10，'
        '21–23→11，24–26→12，27–29→13，30–32→14，33–35→15。\n'
        '难度名含 SC 时优先使用：SC1–4→12，SC5–13→13，SC14及以上→15。'
        '未提供有效定级或判定无法识别的成绩不计入。\n'
        '排行榜使用上述默认倍率；App里的模拟设置不改变云端排行。\n'
        '在 App 上传成绩后，发送 b30 就能查看已绑定QQ的成绩图～'
    )

    def __init__(self, root, catalogue, clock=time.time):
        self.root, self.catalogue_file = Path(root), Path(catalogue)
        self.clock = clock
        self._signature, self._catalogue = None, None

    def catalogue(self):
        stat = self.catalogue_file.stat()
        signature = stat.st_mtime_ns, stat.st_size
        if signature != self._signature:
            doc = read_json(self.catalogue_file)
            if not isinstance(doc.get('server'), str) or not isinstance(doc.get('charts'), list) or len(doc['charts']) > 50000:
                raise Unavailable('invalid catalogue')
            charts = {}
            for row in doc['charts']:
                cid = row.get('cid')
                if type(cid) is not int or cid <= 0 or cid in charts:
                    raise Unavailable('invalid chart identity')
                charts[cid] = row
            self._catalogue = doc['server'], charts
            self._signature = signature
        return self._catalogue

    def board(self, cid=0):
        status = read_json(self.root / 'status.json', 100000)
        revision = status.get('revision', '')
        if status.get('schema') != 2 or not re.fullmatch('[a-f0-9]{64}', revision):
            raise Unavailable('invalid revision')
        server, charts = self.catalogue()
        if cid and cid not in charts:
            raise Unavailable('unknown chart')
        doc = read_json(self.root / revision / ('rt.json' if not cid else f'charts/{cid}.json'))
        if doc.get('schema') != 2 or doc.get('revision') != revision or doc.get('server') != server or doc.get('cid') != cid:
            raise Unavailable('snapshot namespace mismatch')
        updated = number(doc.get('updated'), upper=10000000000000) / 1000
        if updated > self.clock() + 300:
            raise Unavailable('future snapshot')
        players = doc.get('players')
        if not isinstance(players, list) or len(players) > 10000:
            raise Unavailable('invalid board')
        seen, rows = set(), []
        for player in players:
            qq = player.get('qq', '')
            if not isinstance(qq, str) or not re.fullmatch('[1-9][0-9]{4,11}', qq) or qq in seen:
                raise Unavailable('duplicate or invalid player')
            seen.add(qq)
            name = text(player.get('name') or ('玩家' + qq[-4:]))
            if not cid:
                row = dict(qq=qq, name=name, rating=number(player.get('rating'), upper=100),
                           o=number(player.get('o'), upper=100), n=number(player.get('n'), upper=100),
                           oc=number(player.get('oc'), upper=20), nc=number(player.get('nc'), upper=10))
                row['key'] = (-row['rating'],)
            else:
                best = player.get('best')
                if not isinstance(best, list) or len(best) != 1 or best[0].get('cid') != cid:
                    raise Unavailable('incorrect chart score')
                score = best[0]
                row = dict(qq=qq, name=name, rating=number(score.get('rating'), upper=100),
                           accuracy=number(score.get('accuracy'), upper=100), score=number(score.get('score')),
                           judge=number(score.get('judge'), lower=0, upper=5))
                if type(row['score']) is not int or type(row['judge']) is not int:
                    raise Unavailable('invalid score fields')
                row['key'] = (-row['rating'], -row['accuracy'], -row['score'])
            rows.append(row)
        rows.sort(key=lambda row: (*row['key'], row['qq']))
        for index, row in enumerate(rows):
            row['rank'] = rows[index - 1]['rank'] if index and row['key'] == rows[index - 1]['key'] else index + 1
        stamp = datetime.fromtimestamp(updated, ZoneInfo('Asia/Shanghai')).strftime('%m-%d %H:%M')
        warning = ' · 数据更新延迟' if self.clock() - updated > 120 else ''
        if number(doc.get('failed', 0), upper=10000):
            warning += ' · 部分存档读取异常'
        return rows, f'榜单更新 {stamp}{warning}'

    def search(self, query):
        terms = normalize(query).split()
        if not terms:
            return []
        _, charts = self.catalogue()
        matches = [row for row in charts.values() if row.get('search_visible', True) is True and all(term in normalize(' '.join(str(row.get(k, '')) for k in ('title', 'artist', 'creator', 'version', 'cid'))) for term in terms)]
        return sorted(matches, key=lambda row: (normalize(row.get('title', '')) != normalize(query), normalize(row.get('title', '')), row['cid']))

    def search_reply(self, query):
        rows = self.search(query)
        if not rows:
            return '没有找到歌曲，请尝试更短的歌名。'
        lines = [f'找到 {len(rows)} 张谱面（展示前8张）']
        for row in rows[:8]:
            lines.extend([f"{row['cid']} · {text(row.get('title', '未命名'), 40)}",
                          f"{text(row.get('version', ''), 28)} · 歌手 {text(row.get('artist', '未知'), 25)} · 谱师 {text(row.get('creator', '未知'), 25)}"])
        return '\n'.join(lines + ['查询示例：单曲榜 谱面ID'])

    @staticmethod
    def page(rows, value):
        if not value.isascii() or not value.isdigit() or not 1 <= int(value) <= 1000:
            raise ValueError('页码请填写1～1000的整数。')
        page = int(value)
        pages = max(1, (len(rows) + 9) // 10)
        if page > pages:
            raise ValueError(f'共{pages}页，请选择有效页码。')
        return rows[(page - 1) * 10:page * 10], f'第{page}/{pages}页 · {len(rows)}人'

    def execute(self, content):
        content = re.sub(r'<@!?\d+>', '', str(content)).strip().lstrip('/').strip()
        if len(content) > 200:
            return '命令过长，请发送“帮助”查看用法。'
        parts = content.split(maxsplit=1)
        command, arg = (parts + [''])[:2] if parts else ('', '')
        try:
            if command in ('', '帮助', 'help', '菜单'):
                return self.HELP
            if command.casefold() in ('rating', 'rating说明', 'rating规则'):
                return self.RATING_HELP
            if command in ('总榜', 'rt榜', '战力榜'):
                rows, stamp = self.board()
                selected, page = self.page(rows, arg or '1')
                return '\n'.join(['综合 rating 榜 · O20+N10', page] + [f"{r['rank']}. {r['name']} · {r['rating']:.4f}" for r in selected] + [stamp])
            if command == '查排名':
                if not re.fullmatch('[1-9][0-9]{4,11}', arg):
                    return '用法：查排名 QQ号（App中用于上传成绩的QQ）'
                rows, stamp = self.board()
                row = next((r for r in rows if r['qq'] == arg), None)
                if not row:
                    return '榜单没有这个QQ的成绩，请先在App中上传并等待榜单更新。'
                return '\n'.join([f"{row['name']} · 第{row['rank']}名 / {len(rows)}人", f"rating {row['rating']:.4f}",
                                  f"O20 {row['o']:.4f}（{row['oc']}/20）", f"N10 {row['n']:.4f}（{row['nc']}/10）", stamp])
            if command == '查歌':
                return self.search_reply(arg) if arg else '用法：查歌 歌名'
            if command == '单曲榜':
                values = arg.split()
                if not values:
                    return '用法：单曲榜 谱面ID [页码]，也可以先发送“查歌 歌名”。'
                if not re.fullmatch('[1-9][0-9]{0,11}', values[0]):
                    return self.search_reply(arg)
                if len(values) > 2:
                    return '用法：单曲榜 谱面ID [页码]'
                cid = int(values[0])
                chart = self.catalogue()[1].get(cid)
                if not chart:
                    return '谱面ID不在当前谱库，请先发送“查歌 歌名”。'
                rows, stamp = self.board(cid)
                selected, page = self.page(rows, values[1] if len(values) == 2 else '1')
                heading = [f"{text(chart.get('title', '未命名'), 45)} · {text(chart.get('version', ''), 28)}", f'谱面ID {cid} · {page}']
                lines = [f"{r['rank']}. {r['name']} · rating {r['rating']:.4f}\n   {r['accuracy']:.3f}% · {r['score']:,} · {('未知判定' if not r['judge'] else 'ABCDE'[r['judge']-1]+'判')}" for r in selected]
                return '\n'.join(heading + (lines or ['暂无成绩']) + [stamp])
            return '未识别的指令，发送“帮助”查看排行榜命令。'
        except (Unavailable, OSError, KeyError, TypeError, AttributeError, json.JSONDecodeError):
            return '排行榜暂时不可用，请稍后再试。'
        except ValueError as error:
            return str(error)
