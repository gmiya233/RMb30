"""QQ official bot adapter. Only replies to an explicit group mention."""
import asyncio
from collections import OrderedDict
import json
import os
from pathlib import Path
import time
import base64

from ranking_queries import Queries
from b30_commands import B30Commands, command_parts
from song_search import SongSearch
from chart_preview import ChartPreviews


class Gate:
    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.seen, self.last = OrderedDict(), OrderedDict()

    def accept(self, group, user, message):
        now = self.clock()
        if not group or not user or not message:
            return False
        key = (group, message)
        for old, stamp in list(self.seen.items()):
            if now - stamp > 600:
                del self.seen[old]
            else:
                break
        if key in self.seen:
            return False
        self.seen[key] = now
        while len(self.seen) > 4096:
            self.seen.popitem(last=False)
        group_key, user_key = ('group', group), ('user', group, user)
        if now - self.last.get(group_key, -100) < 2 or now - self.last.get(user_key, -100) < 3:
            return False
        for key in (group_key, user_key):
            self.last[key] = now
            self.last.move_to_end(key)
        while len(self.last) > 2048:
            self.last.popitem(last=False)
        return True


def main():
    import botpy
    config_path = os.environ.get('MALODY_BOT_CONFIG') or str(Path(os.environ['CREDENTIALS_DIRECTORY']) / 'config')
    config = json.loads(Path(config_path).read_text())
    credential = Path(os.environ['CREDENTIALS_DIRECTORY']) / 'appsecret'
    secret = credential.read_text().strip()
    assert secret and str(config['appid']).isdigit()
    queries = Queries(config['rankings'], config['catalogue'])
    b30 = B30Commands(queries, Path(os.environ.get('STATE_DIRECTORY', '/var/lib/malody-qqbot')) / 'bindings.sqlite3',
                      config.get('b30', '/var/lib/malody-qqbot/catalog-source/b30'),
                      config.get('font', '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'),
                      config.get('artwork', '/var/lib/malody-qqbot/artwork'))
    gate = Gate()
    song_search = SongSearch(queries, b30.font, [b30.artwork, Path(config['catalogue']).parent / 'thumbs'])
    previews = ChartPreviews(queries, config.get('chart_previews', '/var/lib/malody-qqbot/chart-previews'))
    slots = asyncio.Semaphore(8)
    allowed = set(config.get('allowed_groups', []))

    class Client(botpy.Client):
        async def on_ready(self):
            print('QQ bot connected; read-only ranking queries ready', flush=True)

        async def on_group_at_message_create(self, message):
            if allowed and message.group_openid not in allowed:
                return
            if not gate.accept(message.group_openid, message.author.member_openid, message.id):
                return
            try:
                async with slots:
                    if command_parts(message.content)[0] in b30.COMMANDS:
                        reply = await asyncio.wait_for(asyncio.to_thread(b30.execute, message.group_openid, message.author.member_openid, message.content), timeout=20)
                    elif command_parts(message.content)[0] in previews.COMMANDS:
                        reply = await asyncio.wait_for(asyncio.to_thread(previews.execute, message.content), timeout=10)
                    elif command_parts(message.content)[0] == '查歌':
                        reply = await asyncio.wait_for(asyncio.to_thread(song_search.execute, message.content), timeout=10)
                    else:
                        reply = await asyncio.wait_for(asyncio.to_thread(queries.execute, message.content), timeout=10)
                    if isinstance(reply, bytes):
                        from botpy.http import Route
                        if len(reply) > 4 * 1024 * 1024:
                            raise ValueError('Image exceeds upload limit')
                        # The official API supports file_data; the pinned SDK only wraps URL upload.
                        media = await self.http.request(Route('POST', '/v2/groups/{group_openid}/files', group_openid=message.group_openid),
                            json={'file_type': 1, 'file_data': base64.b64encode(reply).decode('ascii'), 'srv_send_msg': False})
                        await self.api.post_group_message(group_openid=message.group_openid, msg_type=7, msg_id=message.id,
                                                          content=' ', media={'file_info': media['file_info']})
                    else:
                        await self.api.post_group_message(group_openid=message.group_openid, msg_type=0,
                                                          msg_id=message.id, content='\n' + reply[:1800])
            except Exception as error:
                print('Query or reply failed (' + type(error).__name__ + ')', flush=True)
                try:
                    await self.api.post_group_message(group_openid=message.group_openid, msg_type=0, msg_id=message.id,
                        msg_seq=2, content='\n本次查询或图片发送未完成，请稍后再试。')
                except Exception:
                    pass

        async def on_error(self, event_method, *args, **kwargs):
            print('QQ event handler failed; event payload omitted', flush=True)

    # Suppress SDK payload logging: access tokens, messages and identifiers stay out of logs.
    client = Client(intents=botpy.Intents(public_messages=True), is_sandbox=bool(config.get('sandbox', False)),
                    bot_log=False, ext_handlers=False)
    try:
        client.run(appid=str(config['appid']), secret=secret)
    except Exception as error:
        print('QQ connection failed (' + type(error).__name__ + '); check platform permissions and IP allowlist', flush=True)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
