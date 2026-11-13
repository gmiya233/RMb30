from song_buttons import SongCard, send_actions
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
from chart_video import ChartVideos, VideoCooldown
from video_upload import upload_video
from markdown_probe import probe
from button_menu import COMMANDS as MENU_COMMANDS, send_menu
from recommendations import Recommendations, COMMANDS as RECOMMEND_COMMANDS, enable_failure_hint


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
    from song_aliases import SongAliases
    queries.alias_store = SongAliases(Path(os.environ.get('STATE_DIRECTORY', '/var/lib/malody-qqbot')) / 'song-aliases.sqlite3')
    b30 = B30Commands(queries, Path(os.environ.get('STATE_DIRECTORY', '/var/lib/malody-qqbot')) / 'bindings.sqlite3',
                      config.get('b30', '/var/lib/malody-qqbot/catalog-source/b30'),
                      config.get('font', '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'),
                      config.get('artwork', '/var/lib/malody-qqbot/artwork'),legacy_groups=config.get('allowed_groups',[]))
    gate = Gate()
    song_search = SongSearch(queries, b30.font, [b30.artwork, Path(config['catalogue']).parent / 'thumbs'])
    previews = ChartPreviews(queries, config.get('chart_previews', '/var/lib/malody-qqbot/chart-previews'))
    try:
        videos = ChartVideos(queries, previews.root / 'playback', song_search.roots, Path(os.environ.get('STATE_DIRECTORY', '/var/lib/malody-qqbot')) / 'videos', config.get('video_renderer', '/opt/malody-video-renderer/tools/render_gameplay_video.py'))
    except (OSError,ValueError):
        videos=None
        print("Chart video renderer unavailable; other commands remain active",flush=True)
    slots = asyncio.Semaphore(8)
    allowed = set(config.get('allowed_groups', []))
    recommendations = Recommendations(queries, b30.font, song_search.roots, Path(os.environ.get('STATE_DIRECTORY', '/var/lib/malody-qqbot')) / 'recommendations.sqlite3')
    from music_recommendations import MusicRecommendations, COMMANDS as MUSIC_COMMANDS
    from music_share import install_card_fields, extract_playlist
    install_card_fields()
    music_recommendations = MusicRecommendations(Path(os.environ.get('STATE_DIRECTORY', '/var/lib/malody-qqbot')) / 'music-playlists.sqlite3', b30.bindings, b30.font)

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
            if hasattr(image,'music'):
                from ranking_queries import text as clean_text
                song=image.music
                payload['content']='小师妹 · 音乐推荐\n'+clean_text(song['title'],100)+' — '+clean_text(song['artist'],100)+'\nhttps://y.qq.com/n/ryqq_v2/songDetail/'+song['mid']
            if message_id: payload.update(msg_id=message_id,msg_seq=1)
            result=await self.http.request(Route('POST','/v2/groups/{group_openid}/messages',group_openid=group),json=payload)
            if not isinstance(result,dict) or not result.get('id'):
                raise TimeoutError('Image message acknowledgement missing')
            if hasattr(image,'music'):
                await asyncio.to_thread(music_recommendations.delivered,group,image.music['mid'])
            async def send_song_actions(data):
                return await self.http.request(Route('POST','/v2/groups/{group_openid}/messages',group_openid=group),json=data)
            await send_actions(send_song_actions, image, message_id)

        async def video_reply(self,message):
            from botpy.http import Route
            import aiohttp
            sequence=1
            async def say(content):
                nonlocal sequence
                await self.api.post_group_message(group_openid=message.group_openid,msg_type=0,msg_id=message.id,msg_seq=sequence,content='\n'+content)
                sequence+=1
            try:
                if videos is None:
                    await say("谱面视频服务暂不可用，请稍后重试。其他查询功能不受影响。");return
                plan=await asyncio.to_thread(videos.resolve,message.content)
                if isinstance(plan,str):
                    await say(plan);return
                if not await asyncio.to_thread(videos.cached,plan):
                    task,first=videos.start(plan)
                    await say('这是该谱面当前版本的首次生成，正在制作完整视频，请稍候。完成后会发送，后续查询直接使用缓存。' if first else '这张谱面的视频正在生成中，已加入同一任务，无需重复生成。')
                    try:await asyncio.wait_for(asyncio.shield(task),timeout=150)
                    except asyncio.TimeoutError:
                        await say('视频仍在后台排队或生成，请稍后再次发送同一指令；完成后会直接读取缓存。');return
                async def request(endpoint,payload):
                    return await self.http.request(Route('POST','/v2/groups/{group_openid}/'+endpoint,group_openid=message.group_openid),json=payload)
                async def put(url,data):
                    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=60)) as session:
                        async with session.put(url,data=data,allow_redirects=False) as response:
                            if response.status not in (200,201,204):raise ValueError('Video part upload failed')
                            await response.read()
                media=await upload_video(request,put,plan.output)
                result=await self.http.request(Route('POST','/v2/groups/{group_openid}/messages',group_openid=message.group_openid),json={'msg_type':7,'msg_id':message.id,'msg_seq':sequence,'content':' ','media':{'file_info':media['file_info']}})
                sequence+=1
                if not isinstance(result,dict) or not result.get('id'):raise TimeoutError('Video send acknowledgement missing')
                print('Chart video: accepted',flush=True)
            except VideoCooldown:
                await say(VideoCooldown.REPLY)
            except ValueError as error:
                print('Chart video: unavailable ('+type(error).__name__+')',flush=True)
                await say('视频生成队列已满，请稍后重试。' if str(error).startswith('视频生成队列') else '本次视频生成或上传未完成，请稍后重试；已生成的视频缓存会保留。')
            except Exception as error:
                print('Chart video: failed ('+type(error).__name__+')',flush=True)
                await say('本次视频生成或上传未完成，请稍后重试；已生成的视频缓存会保留。')

        async def recommendation_loop(self):
            while True:
                try:
                    groups=await asyncio.to_thread(recommendations.due,allowed)
                    for group in groups:
                        ok=False
                        try:
                            async with slots:
                                choose_music=await asyncio.to_thread(recommendations.kind,group) == 'music'
                                maker=music_recommendations.card if choose_music else recommendations.card
                                image=await asyncio.wait_for(asyncio.to_thread(maker,group),timeout=30)
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
                if command_parts(message.content)[0] in ChartVideos.COMMANDS:
                    await self.video_reply(message)
                    return
                async with slots:
                    command, argument = command_parts(message.content)
                    music_content=message.content
                    if command == '绑定歌单':
                        shared_pid=extract_playlist(getattr(message,'share_data',{}),argument)
                        if shared_pid:music_content='绑定歌单 '+shared_pid
                    elif command not in MUSIC_COMMANDS:
                        pending_music=await asyncio.to_thread(music_recommendations.binding_input,message.group_openid,message.author.member_openid,message)
                        if pending_music:
                            music_content=pending_music
                            command,argument=command_parts(pending_music)
                    if command in MUSIC_COMMANDS:
                        try:
                            music_reply=await asyncio.wait_for(asyncio.to_thread(music_recommendations.execute,message.group_openid,message.author.member_openid,music_content),timeout=30)
                        except (ValueError,TimeoutError) as error:
                            music_reply=str(error) or '音乐推荐暂未就绪，请稍后再试。'
                        if isinstance(music_reply,bytes):
                            await self.send_card(message.group_openid,music_reply,message.id)
                        else:
                            await self.api.post_group_message(group_openid=message.group_openid,msg_type=0,msg_id=message.id,content='\n'+music_reply[:1800])
                        return
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
                                reply=enable_failure_hint(error)
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
                        await send_menu(send_menu_reply, message.id, queries.HELP, page=('传谱菜单' if command_parts(message.content)[0] == '更多功能' else command_parts(message.content)[0]))
                        return
                    if command_parts(message.content)[0] in ('markdown测试', 'md测试'):
                        from botpy.http import Route
                        async def send_probe(payload):
                            return await self.http.request(Route('POST', '/v2/groups/{group_openid}/messages', group_openid=message.group_openid), json=payload)
                        await probe(send_probe, message.id)
                        return
                    if command_parts(message.content)[0] in ('六维算法', '六维规则', '六维参考'):
                        from radar_rules import explanation
                        reply = explanation()
                    elif command_parts(message.content)[0] == '推分推荐':
                        from push_recommend import PushRecommendations
                        reply = await asyncio.wait_for(asyncio.to_thread(PushRecommendations(queries, b30).execute, message.group_openid, message.author.member_openid, message.content), timeout=15)
                    elif command_parts(message.content)[0] in b30.COMMANDS:
                        reply = await asyncio.wait_for(asyncio.to_thread(b30.execute, message.group_openid, message.author.member_openid, message.content), timeout=20)
                    elif command_parts(message.content)[0] in previews.COMMANDS:
                        reply = await asyncio.wait_for(asyncio.to_thread(previews.execute, message.content), timeout=10)
                    elif command_parts(message.content)[0] in ('等级', '等级表'):
                        from level_table import LevelTables
                        reply = await asyncio.wait_for(asyncio.to_thread(LevelTables(queries, b30.font, song_search.roots).execute, message.content), timeout=30)
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
                        async def send_search_actions(data):
                            return await self.http.request(Route('POST','/v2/groups/{group_openid}/messages',group_openid=message.group_openid),json=data)
                        await send_actions(send_search_actions, reply, message.id)
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
