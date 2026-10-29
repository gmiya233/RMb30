import json
import re
import time
from pathlib import Path

from bindings import Bindings
from ranking_queries import read_json, Unavailable, number


def command_parts(content):
    content = re.sub(r'<@!?\d+>', '', str(content)).strip().lstrip('/').strip()
    parts = content.split(maxsplit=1)
    return (parts[0].casefold(), parts[1] if len(parts) > 1 else '') if parts else ('', '')


class B30Commands:
    COMMANDS = {'b30', '6kb30', '6k', '6k成绩图', '绑定', '绑定qq', '解绑', '我的绑定'}

    def __init__(self, queries, bindings, summaries, font, artwork):
        self.queries = queries
        self.bindings = Bindings(bindings)
        self.summaries, self.font, self.artwork = Path(summaries), font, artwork

    def execute(self, group, member, content):
        command, arg = command_parts(content)
        if command not in self.COMMANDS:
            return None
        six=command in ('6kb30','6k成绩图') or (command=='6k' and arg.casefold()=='b30')
        if command=='6k':
            if not six: return '用法：6K B30'
            arg=''
        if len(arg) > 100:
            return '参数过长，请发送“帮助”查看用法。'
        if command in ('绑定', '绑定qq'):
            try:
                self.bindings.set(group, member, arg)
            except ValueError as error:
                return str(error)
            return '已设置本群默认查询QQ。发送 b30 即可查看云端成绩图；如尚未上传成绩，请先在App中同步云端。'
        if command == '解绑':
            self.bindings.remove(group, member)
            return '已解除本群的默认查询QQ，云端成绩保留。'
        qq = self.bindings.get(group, member)
        if not qq:
            return '首次使用请先发送“绑定 你的QQ号”（App上传成绩使用的QQ），以后在本群发送 b30 即可自动出图。官方机器人不能直接获取真实QQ号。'
        if command == '我的绑定':
            return f'本群默认查询QQ：{qq}。更换请发送“绑定 新QQ号”，解除请发送“解绑”。'
        if arg:
            return '直接发送 b30 即可查询已绑定的QQ；更换请发送“绑定 新QQ号”。'
        try:
            players, _ = self.queries.board(six=True) if six else self.queries.board()
            player = next((p for p in players if p['qq'] == qq), None)
            if not player:
                return '这个QQ暂无有效6K成绩，请先在App中同步云端。' if six else '这个QQ暂无云端成绩，请先在App中同步云端，再发送 b30。'
            status = read_json(self.queries.root / 'status.json', 100000)
            revision = status['revision']
            if not re.fullmatch('[a-f0-9]{64}', revision):
                raise Unavailable('invalid revision')
            path = self.summaries / revision / (qq + ('-6k.json' if six else '.json'))
            if not path.exists():
                return 'B30云端摘要正在更新，请稍后再试。'
            doc = read_json(path, 300000)
            if doc.get('schema') != 1 or doc.get('qq') != qq or doc.get('revision') != revision or doc.get('server') != self.queries.catalogue()[0]:
                raise Unavailable('wrong summary identity')
            for key in ('rating', 'o', 'n'):
                if abs(number(doc.get(key), upper=100) - player[key]) > 1e-10:
                    return '新成绩已收到，B30云端摘要正在更新，请稍后再试。'
            if six and doc.get('keys')!=6: raise Unavailable('wrong lane summary')
            seen = set()
            for key, maximum in [('old', 20), ('new', 10)]:
                rows = doc[key]
                if not isinstance(rows, list) or len(rows) > maximum:
                    raise Unavailable('invalid slot count')
                for row in rows:
                    if type(row.get('cid')) is not int or row['cid'] in seen:
                        raise Unavailable('duplicate chart')
                    seen.add(row['cid'])
                    number(row.get('rating'), upper=100)
                    number(row.get('accuracy'), upper=100)
                    number(row.get('score'))
                    if type(row.get('score')) is not int or type(row.get('judge')) is not int:
                        raise Unavailable('invalid score field')
            number(doc['updated'], upper=10000000000000)
            from b30_image import render
            return render(doc, self.font, self.artwork)
        except (ValueError, KeyError, OSError, TypeError, AttributeError, json.JSONDecodeError):
            return 'B30暂时无法生成，请稍后再试。'
