import asyncio
from pathlib import Path
import sys,unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server/qqbot'))
from button_menu import payload,send_menu,COMMANDS,PAGES
from b30_commands import command_parts

class MenuTests(unittest.TestCase):
 def buttons(self,page='菜单'):
  return [b for r in payload('fixture',page=page)['keyboard']['content']['rows'] for b in r['buttons']]
 def test_readable_category_layout_and_complete_reachable_commands(self):
  self.assertEqual(len(self.buttons()),7)
  found=set();visited=set();pending=['菜单']
  while pending:
   page=pending.pop()
   if page in visited:continue
   visited.add(page);data=payload('fixture',page=page)
   self.assertLessEqual(len(data['keyboard']['content']['rows']),5)
   self.assertTrue(all(1<=len(r['buttons'])<=2 for r in data['keyboard']['content']['rows']))
   for b in self.buttons(page):
    command,arg=command_parts(b['action']['data'])
    if command in PAGES:pending.append(command)
    else:found.add(command)
    self.assertEqual(b['action']['permission']['type'],2)
  self.assertEqual(visited,set(PAGES))
  self.assertTrue({'b30','6kb30','rating','推分推荐','等级','歌曲别名','查歌','谱面预览','谱面视频','总榜','6k榜','活跃榜','单曲榜','查排名','歌曲推荐','推荐状态','开启推荐','关闭推荐','绑定','我的绑定','解绑','新手教程','传谱','确认传谱','取消传谱','传谱状态','传谱认证','传谱权限','撤销传谱权限','传谱权限列表'}<=found)
 def test_input_commands_do_not_send_incomplete_parameters(self):
  needs={'查歌','等级','歌曲别名','谱面预览','谱面视频','单曲榜','查排名','绑定','绑定歌单','传谱','确认传谱','取消传谱','传谱状态','传谱权限','撤销传谱权限'}
  for page in PAGES:
   for b in self.buttons(page):
    self.assertEqual(b['action']['enter'],command_parts(b['action']['data'])[0] not in needs)
  self.assertIn('排行榜菜单',COMMANDS)
  self.assertIn('更多功能',COMMANDS)
 def test_delivery_and_category_specific_fallback(self):
  async def run(fail):
   calls=[]
   async def send(p):
    calls.append(p)
    if fail and len(calls)==1:raise PermissionError('synthetic')
    return {'id':'fixture'}
   await send_menu(send,'fixture','old help',page='排行榜菜单')
   self.assertEqual(len(calls),2 if fail else 1)
   if fail:
    self.assertEqual(calls[-1]['msg_type'],0)
    self.assertIn('总战力榜',calls[-1]['content'])
    self.assertIn('月活跃榜',calls[-1]['content'])
    self.assertEqual(calls[-1]['msg_seq'],2)
  asyncio.run(run(False));asyncio.run(run(True))
if __name__=='__main__':unittest.main()
