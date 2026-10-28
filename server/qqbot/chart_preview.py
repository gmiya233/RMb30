"""Read server-rendered previews without access to raw chart or player files."""
import hashlib
import json
from pathlib import Path
import re

from b30_commands import command_parts
from ranking_queries import text


class ChartPreviews:
    COMMANDS = {'谱面预览', '预览谱面'}

    def __init__(self, queries, root):
        self.queries, self.root = queries, Path(root)

    def execute(self, content):
        command, argument = command_parts(content)
        if command not in self.COMMANDS or len(content) > 200:
            return '用法：谱面预览 谱面ID [页码]，也可以输入歌名。'
        page = 1
        if argument.isdecimal():
            chart_id = int(argument)
        else:
            parts = argument.rsplit(' ', 1)
            if len(parts) == 2 and parts[1].isdecimal():
                argument, page = parts[0], int(parts[1])
            chart_id = int(argument) if argument.isdecimal() else None
        rows = self.queries.catalogue()[1]
        if chart_id is not None:
            found = [rows[chart_id]] if chart_id in rows and rows[chart_id].get('search_visible', True) else []
        else:
            found = self.queries.search(argument) if argument else []
        if len(found) != 1:
            if not found:
                return '没有找到可预览的谱面。用法：谱面预览 谱面ID [页码]。'
            return '请选择具体谱面ID：\n' + '\n'.join(f"{c['cid']} · {text(c.get('title'), 38)} · {text(c.get('version'), 35)}" for c in found[:8])
        cid = found[0]['cid']
        try:
            file = self.root / f'{cid}.json'
            if file.is_symlink() or file.stat().st_size > 16384:
                raise ValueError('Invalid manifest')
            doc = json.loads(file.read_bytes())
            # The public bot catalogue includes server_hash solely to bind preview identity.
            if doc.get('schema') != 1 or doc.get('server') != self.queries.catalogue()[0] or doc.get('cid') != cid or doc.get('server_hash') != found[0].get('server_hash'):
                raise ValueError('Preview namespace mismatch')
            pages = doc.get('pages', [])
            if doc.get('status') != 'ready' or not 1 <= len(pages) <= 8:
                raise ValueError('Preview not ready')
            if not 1 <= page <= len(pages):
                return f'这张谱面共 {len(pages)} 页，请发送：谱面预览 {cid} 1'
            image = pages[page - 1]
            name = image['image']
            if not re.fullmatch(rf'{cid}-[a-f0-9]{{64}}\.png', name):
                raise ValueError('Invalid image name')
            source = self.root / name
            if source.is_symlink() or not 0 < source.stat().st_size <= 4 * 1024 * 1024:
                raise ValueError('Invalid preview size')
            data = source.read_bytes()
            if len(data) != image['bytes'] or hashlib.sha256(data).hexdigest() != image['sha256'] or not data.startswith(b'\x89PNG\r\n\x1a\n'):
                raise ValueError('Preview checksum mismatch')
            return data
        except (OSError, ValueError, KeyError, TypeError):
            return '该谱面预览暂未就绪，请稍后再试。目前支持 4K／5K／6K Slide 直轨展开图。'
