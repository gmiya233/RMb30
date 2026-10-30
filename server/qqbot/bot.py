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
from markdown_probe import probe
from button_menu import COMMANDS as MENU_COMMANDS, send_menu
from recommendations import Recommendations, COMMANDS as RECOMMEND_COMMANDS


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
    recommendations = Recommendations(queries, b30.font, song_search.roots, Path(os.environ.get('STATE_DIRECTORY', '/var/lib/malody-qqbot')) / 'recommendations.sqlite3')

    class Client(botpy.Client):
        async def send_card(self, group, image, message_id=None):
            from botpy.http import Route
            if not 0 < len(image) <= 4 * 1024 * 1024:
                raise ValueError('Image exceeds upload limit')
            media = await self.http.request(Route('POST', '/v2/groups/{group_openid}/files', group_openid=group),
                json={'file_type':1, 'file_data':base64.b64encode(image).decode('ascii'), 'srv_send_msg':False})
            if not isinstance(media,dict) or not media.get('file_info'):
                raise TimeoutError('Image upload acknowledgement missing')
            payload={'msg_type':7, 'content':' ', 'media':{'file_info':media['file_info']}}
            if message_id: payload.update(msg_id=message_id,msg_seq=1)
            result=await self.http.request(Route('POST','/v2/groups/{group_openid}/messages',group_openid=group),json=payload)
            if not isinstance(result,dict) or not result.get('id'):
                raise TimeoutError('Image message acknowledgement missing')

        async def recommendation_loop(self):
            while True:
                try:
                    groups=await asyncio.to_thread(recommendations.due,allowed)
                    for group in groups:
                        ok=False
                        try:
                            async with slots:
                                image=await asyncio.wait_for(asyncio.to_thread(recommendations.card,group),timeout=30)
                                await self.send_card(group,image)
                                ok=True
                                print('Scheduled recommendation: accepted',flush=True)
                        except Exception as error:
                            print('Scheduled recommendation: failed ('+type(error).__name__+')',flush=True)
                        await asyncio.to_thread(recommendations.finish,group,ok)
                except Exception as error:
                    print('Recommendation scheduler: failed ('+type(error).__name__+')',flush=True)
                await asyncio.sleep(30)

        async def on_ready(self):
            print('QQ bot connected; read-only ranking queries ready', flush=True)
            if not getattr(self,'recommendation_task',None) or self.recommendation_task.done():
                self.recommendation_task=asyncio.create_task(self.recommendation_loop())

        async def on_group_at_message_create(self, message):
            if allowed and message.group_openid not in allowed:
                return
            if not gate.accept(message.group_openid, message.author.member_openid, message.id):
                return
            try:
                async with slots:
                    command, argument = command_parts(message.content)
                    if command in RECOMMEND_COMMANDS:
                        group=message.group_openid
                        if command == '关闭推荐':
                            reply=await asyncio.to_thread(recommendations.disable,group)
                        elif command == '推荐状态':
                            reply=await asyncio.to_thread(recommendations.status,group)
                        elif command == '开启推荐':
                            # Send without msg_id to verify real proactive delivery first.
                            try:
                                image=await asyncio.wait_for(asyncio.to_thread(recommendations.card,group),timeout=30)
                                await self.send_card(group,image)
                            except Exception as error:
                                print('Enable recommendation: proactive probe failed ('+type(error).__name__+')',flush=True)
                                reply='自动推荐尚未开启：主动发送未成功。请在QQ群机器人设置允许主动推送后重试；仍可发送“歌曲推荐”手动查询。'
                            else:
                                reply=await asyncio.to_thread(recommendations.enable,group)
                                print('Enable recommendation: proactive delivery accepted; schedule enabled',flush=True)
                        else:
                            image=await asyncio.wait_for(asyncio.to_thread(recommendations.card,group),timeout=30)
                            await self.send_card(group,image,message.id)
                            print('Manual recommendation: accepted',flush=True)
                            return
                        await self.api.post_group_message(group_openid=group,msg_type=0,msg_id=message.id,content='\n'+reply)
                        return
                    if command_parts(message.content)[0] in MENU_COMMANDS:
                        from botpy.http import Route
                        async def send_menu_reply(payload):
                            return await self.http.request(Route('POST', '/v2/groups/{group_openid}/messages', group_openid=message.group_openid), json=payload)
                        await send_menu(send_menu_reply, message.id, queries.HELP)
                        return
                    if command_parts(message.content)[0] in ('markdown测试', 'md测试'):
                        from botpy.http import Route
                        async def send_probe(payload):
                            return await self.http.request(Route('POST', '/v2/groups/{group_openid}/messages', group_openid=message.group_openid), json=payload)
                        await probe(send_probe, message.id)
                        return
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
                        if not isinstance(media, dict) or not media.get('file_info'):
                            raise TimeoutError('QQ image upload did not return media')
                        await self.api.post_group_message(group_openid=message.group_openid, msg_type=7, msg_id=message.id,
                                                          content=' ', media={'file_info': media['file_info']})
                    else:
                        await self.api.post_group_message(group_openid=message.group_openid, msg_type=0,
                                                          msg_id=message.id, content='\n' + reply[:1800])
            except TimeoutError:
                print('QQ query or image upload timed out; payload omitted', flush=True)
                try:
                    await self.api.post_group_message(group_openid=message.group_openid, msg_type=0, msg_id=message.id,
                        msg_seq=2, content='\nQQ 图片上传超时，请稍后重试。')
                except Exception:
                    pass
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
    client = Client(timeout=30, intents=botpy.Intents(public_messages=True), is_sandbox=bool(config.get('sandbox', False)),
                    bot_log=False, ext_handlers=False)
    try:
        client.run(appid=str(config['appid']), secret=secret)
    except Exception as error:
        print('QQ connection failed (' + type(error).__name__ + '); check platform permissions and IP allowlist', flush=True)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
