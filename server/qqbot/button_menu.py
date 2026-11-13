"""Small category menus: no more than two readable buttons per row."""
PAGES = {
 '菜单': ('功能菜单','先选一个分类吧～',[
  ('成绩菜单','成绩菜单',True),('排行榜菜单','排行榜菜单',True),
  ('歌曲与谱面','歌曲菜单',True),('歌曲推荐','推荐菜单',True),
  ('账号设置','账号菜单',True),('传谱菜单','传谱菜单',True),('新手教程','新手教程',True)]),
 '成绩菜单': ('我的成绩','查询成绩，或寻找稳妥的推分目标。',[
  ('我的 B30','b30',True),('6K B30','6kb30',True),('rating规则','rating',True),('推分推荐','推分推荐',True)]),
 '排行榜菜单': ('排行榜','选择榜单。单曲榜和玩家排名需要填写参数。',[
  ('总战力榜','总榜',True),('6K 排行','6K榜',True),('累计活跃榜','活跃榜 累计',True),('月活跃榜','活跃榜 月',True),('单曲排行','单曲榜 ',False),('玩家排名','查排名 ',False)]),
 '歌曲菜单': ('歌曲与谱面','按钮会填入指令，补上歌名、等级或编号后发送。',[
  ('查找歌曲','查歌 ',False),('等级总表','等级 ',False),('谱面预览','谱面预览 ',False),('谱面视频','谱面视频 ',False),('添加别名','歌曲别名 ',False),('六维算法与参考','六维算法',True)]),
 '推荐菜单': ('歌曲推荐','谱面与音乐每小时交替推送；绑定公开歌单后加入公共音乐池。',[
  ('随机推荐','歌曲推荐',True),('推荐状态','推荐状态',True),('开启推荐','开启推荐',True),('关闭推荐','关闭推荐',True),('音乐推荐','音乐推荐',True),('绑定歌单','绑定歌单 ',False),('我的歌单','我的歌单',True),('解绑歌单','解绑歌单',True)]),
 '账号菜单': ('账号设置','绑定App上传成绩时使用的QQ，跨群共用。',[
  ('绑定 QQ','绑定 ',False),('我的绑定','我的绑定',True),('解除绑定','解绑',True),('新手教程','新手教程',True)]),
 '传谱菜单': ('传谱','填写等级并附上MCZ，核对预览后使用任务编号确认或取消。',[
  ('开始传谱','传谱 ',False),('传谱状态','传谱状态 ',False),('确认传谱','确认传谱 ',False),('取消传谱','取消传谱 ',False),('传谱管理','传谱管理菜单',True)]),
 '传谱管理菜单': ('传谱管理','操作沿用现有权限检查，看到按钮不代表获得权限。',[
  ('传谱认证','传谱认证',True),('权限列表','传谱权限列表',True),('授予权限','传谱权限 ',False),('撤销权限','撤销传谱权限 ',False),('返回传谱','传谱菜单',True)]),
}
COMMANDS=set(PAGES)|{'','帮助','help','更多功能'}

def payload(message_id,more=False,page='菜单'):
 key='传谱菜单' if more else (page if page in PAGES else '菜单')
 title,description,items=PAGES[key];items=list(items)
 if key!='菜单':items.append(('返回主菜单','菜单',True))
 rows=[]
 for offset in range(0,len(items),2):
  buttons=[]
  for index,(label,command,enter) in enumerate(items[offset:offset+2]):
   buttons.append(dict(id=f'{key}-{offset+index}',render_data=dict(label=label,visited_label=label,style=1 if key=='菜单' else 0),action=dict(type=2,permission=dict(type=2),data=command,enter=enter,unsupport_tips='请升级QQ或手动输入对应指令。')))
  rows.append(dict(buttons=buttons))
 return dict(msg_type=2,msg_id=message_id,msg_seq=1,markdown=dict(content=f'# 小师妹 · {title}\n{description}'),keyboard=dict(content=dict(rows=rows)))

async def send_menu(send,message_id,fallback,more=False,page='菜单'):
 data=payload(message_id,more,page)
 try:
  result=await send(data)
  if not isinstance(result,dict) or not result.get('id'):raise TimeoutError('Menu acknowledgement missing')
  print('Button menu: accepted',flush=True)
 except Exception as error:
  print('Button menu: plain-text fallback ('+type(error).__name__+')',flush=True)
  commands=[b['render_data']['label']+'：'+b['action']['data'].strip() for row in data['keyboard']['content']['rows'] for b in row['buttons']]
  await send(dict(msg_type=0,msg_id=message_id,msg_seq=2,content='\n'+data['markdown']['content'].replace('# ','')+'\n'+'\n'.join(commands)))
