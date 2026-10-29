import asyncio
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server/qqbot'))
from button_menu import payload,send_menu,COMMANDS
from b30_commands import command_parts


class MenuTests(unittest.TestCase):
    def test_actions_need_input_before_sending(self):
        menu=payload('fixture')
        buttons=[b for row in menu['keyboard']['content']['rows'] for b in row['buttons']]
        self.assertEqual(len(buttons),15)
        self.assertEqual(len({b['id'] for b in buttons}),15)
        for b in buttons:
            action=b['action'];command,arg=command_parts(action['data'])
            self.assertEqual(action['type'],2)
            self.assertEqual(action['permission']['type'],2)
            self.assertEqual(action['enter'],command in {'b30','总榜','rating','我的绑定','歌曲推荐','开启推荐','关闭推荐','活跃榜','6k榜'})
            self.assertEqual(bool(arg),command=="活跃榜")
        self.assertIn(command_parts('<@!123> /菜单')[0],COMMANDS)
        self.assertEqual(menu['msg_type'],2)

    def test_delivery_and_fallback(self):
        async def run(fail):
            calls=[]
            async def send(p):
                calls.append(p)
                if fail and len(calls)==1: raise PermissionError('synthetic')
                return {'id':'fixture'}
            await send_menu(send,'fixture','text help')
            self.assertEqual(len(calls),2 if fail else 1)
            if fail:
                self.assertEqual(calls[-1]['msg_type'],0)
                self.assertEqual(calls[-1]['content'],'\ntext help')
                self.assertEqual(calls[-1]['msg_seq'],2)
        asyncio.run(run(False));asyncio.run(run(True))

if __name__=='__main__': unittest.main()
