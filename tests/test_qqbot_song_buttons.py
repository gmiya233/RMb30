import asyncio
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'server/qqbot'))
from song_buttons import SongCard, payload, send_actions

class SongButtonTests(unittest.TestCase):
    def test_exact_chart_commands_and_response_isolation(self):
        a = SongCard(b'image', [dict(cid=42)])
        b = SongCard(b'other', [dict(cid=99)])
        self.assertIsInstance(a, bytes)
        buttons = payload(a, 'message')['keyboard']['content']['rows'][0]['buttons']
        self.assertEqual([r['action']['data'] for r in buttons], ['谱面预览 42', '谱面视频 42', '歌曲别名 42 '])
        self.assertTrue(all(r['action']['enter'] for r in buttons[:2]))
        self.assertFalse(buttons[2]['action']['enter'])
        self.assertIn('99', payload(b)['keyboard']['content']['rows'][0]['buttons'][0]['action']['data'])
        self.assertNotIn('msg_id', payload(b))
        self.assertIsNone(payload(b'normal-b30'))

    def test_multi_difficulty_limits_and_failed_buttons_preserve_image(self):
        card = SongCard(b'image', [dict(cid=i,version='SC9') for i in range(1,8)])
        self.assertEqual(len(payload(card)['keyboard']['content']['rows']), 5)
        async def fail(data):
            raise PermissionError()
        asyncio.run(send_actions(fail, card, 'message'))

if __name__ == '__main__': unittest.main()
