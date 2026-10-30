"""Public command menu, sent only in response to a group mention."""
COMMANDS = {'', '菜单', '帮助', 'help'}
ROWS = (
    (('我的 B30', 'b30', True), ('总战力榜', '总榜', True), ('6K B30', '6kb30', True), ('rating 规则', 'rating', True)),
    (('查找歌曲', '查歌 ', False), ('单曲排行', '单曲榜 ', False), ('谱面预览', '谱面预览 ', False)),
    (('绑定 QQ', '绑定 ', False), ('我的绑定', '我的绑定', True), ('玩家排名', '查排名 ', False)),
    (('累计活跃榜', '活跃榜 累计', True), ('月活跃榜', '活跃榜 月', True), ('6K 排行', '6K榜', True)),
    (('歌曲推荐', '歌曲推荐', True), ('开启推荐', '开启推荐', True), ('关闭推荐', '关闭推荐', True)),
)


def payload(message_id):
    rows = []
    for row_index, row in enumerate(ROWS):
        buttons = []
        for index, (label, command, enter) in enumerate(row):
            buttons.append({'id': f'menu-{row_index}-{index}',
                'render_data': {'label': label, 'visited_label': label, 'style': 1 if row_index == 0 else 0},
                'action': {'type': 2, 'permission': {'type': 2}, 'data': command, 'enter': enter,
                           'unsupport_tips': '请升级QQ，或手动输入对应指令。'}})
        rows.append({'buttons': buttons})
    return {'msg_type': 2, 'msg_id': message_id, 'msg_seq': 1,
        'markdown': {'content': '# 小师妹 · 音游助手\n**成绩与排行**\n查看我的 B30、总战力榜和单曲排行。\n\n**歌曲与谱面**\n搜索歌名、查看歌曲信息和整首谱面预览。\n\n**歌曲推荐**\n手动抽歌，或开启本群每2小时自动推荐（曲绘、BPM、难度和排行）。\n\n首次使用 B30：先在 App 同步云端成绩，再点「绑定 QQ」填写同一个 QQ 号。\n查歌、排行和谱面预览按钮会填入指令，请继续输入歌名、谱面 ID 或 QQ 号。'},
        'keyboard': {'content': {'rows': rows}}}


async def send_menu(send, message_id, fallback):
    try:
        result = await send(payload(message_id))
        if not isinstance(result, dict) or not result.get('id'):
            raise TimeoutError('Menu acknowledgement missing')
        print('Button menu: accepted', flush=True)
    except Exception as error:
        print('Button menu: plain-text fallback (' + type(error).__name__ + ')', flush=True)
        await send({'msg_type': 0, 'msg_id': message_id, 'msg_seq': 2, 'content': '\n' + fallback[:1800]})
