"""Per-response song actions; carry metadata with bytes to avoid concurrent mixups."""
class SongCard(bytes):
    def __new__(cls, image, charts):
        instance = super().__new__(cls, image)
        instance.charts = tuple((c['cid'], str(c.get('version') or '默认难度')) for c in charts)
        return instance


def payload(card, message_id=None):
    charts = getattr(card, 'charts', ())
    if not charts:
        return None
    rows = []
    for cid, version in charts[:5]:
        if type(cid) is not int or cid <= 0:
            raise ValueError('Invalid chart ID')
        buttons = []
        for index, command in enumerate(('谱面预览', '谱面视频')):
            label = command if len(charts) == 1 else command + ' · ' + version[:12]
            buttons.append(dict(id=f'song-{cid}-{index}',
                render_data=dict(label=label, visited_label=label, style=1),
                action=dict(type=2, permission=dict(type=2), data=f'{command} {cid}', enter=True,
                            unsupport_tips='请升级QQ或按谱面ID手动查询。')))
        buttons.append(dict(id=f'alias-{cid}',
            render_data=dict(label='添加别名', visited_label='添加别名', style=0),
            action=dict(type=2, permission=dict(type=2), data=f'歌曲别名 {cid} ', enter=False,
                        unsupport_tips='请手动输入：歌曲别名 歌曲编号 别名')))
        rows.append(dict(buttons=buttons))
    content = '选择下方按钮，查看这首歌的谱面～'
    if len(charts) > 5:
        content += '\n按钮展示前5个难度，其余请按图片中的谱面ID查询。'
    result = dict(msg_type=2, markdown=dict(content=content), keyboard=dict(content=dict(rows=rows)))
    if message_id:
        result.update(msg_id=message_id, msg_seq=2)
    return result


async def send_actions(send, card, message_id=None):
    data = payload(card, message_id)
    if data is None:
        return
    try:
        result = await send(data)
        if not isinstance(result, dict) or not result.get('id'):
            raise TimeoutError('Song buttons acknowledgement missing')
    except Exception as error:
        # The song image has already succeeded; don't report its delivery as failed.
        print('Song buttons unavailable (' + type(error).__name__ + ')', flush=True)
