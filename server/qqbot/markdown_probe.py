"""Opt-in capability probe: only sends replies to the triggering mention."""

def payloads(message_id):
    base = {'msg_type': 2, 'msg_id': message_id}
    plain = dict(base, msg_seq=1, markdown={'content': '# 小师妹 Markdown 测试\n**这行是加粗文字。**\n下一条会测试消息按钮。'})
    buttons = []
    for label, command, enter in [('我的B30','b30',True),('总榜','总榜',True),('谱面预览','谱面预览 ',False)]:
        buttons.append({'id':str(len(buttons)+1),'render_data':{'label':label,'visited_label':label,'style':1},
                        'action':{'type':2,'permission':{'type':2},'data':command,'enter':enter,'unsupport_tips':'请升级QQ后使用'}})
    menu = dict(base, msg_seq=2, markdown={'content':'# 小师妹按钮测试\n请查看这条消息下方是否显示三个按钮。'},
                keyboard={'content':{'rows':[{'buttons':buttons}]}})
    return plain, menu


async def probe(send, message_id):
    plain, menu = payloads(message_id)
    try:
        result = await send(plain)
        if not isinstance(result,dict) or not result.get('id'):
            raise TimeoutError('No Markdown acknowledgement')
    except Exception as error:
        print('Markdown probe: text not confirmed ('+type(error).__name__+')',flush=True)
        await send({'msg_type':0,'msg_id':message_id,'msg_seq':3,'content':'Markdown 测试未通过或请求超时，暂不能确认可用。'})
        return
    print('Markdown probe: text accepted',flush=True)
    try:
        result = await send(menu)
        if not isinstance(result,dict) or not result.get('id'):
            raise TimeoutError('No keyboard acknowledgement')
        print('Markdown probe: keyboard accepted; client display confirmation required',flush=True)
    except Exception as error:
        print('Markdown probe: keyboard not confirmed ('+type(error).__name__+')',flush=True)
        await send({'msg_type':0,'msg_id':message_id,'msg_seq':3,'content':'Markdown 已发送成功，但自定义按钮被拒绝或未获确认。请确认上一条是否正常显示标题和加粗文字。'})
