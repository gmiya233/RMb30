import asyncio
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'server/qqbot'))
from music_preview import MusicPreview,preview_url


class PreviewTests(unittest.TestCase):
    def test_only_official_trial_url_no_full_song_fallback(self):
        detail={'code':0,'data':[{'file':{'media_mid':'MediaMid','size_try':100}}]}
        result={'req_0':{'code':0,'data':{'midurlinfo':[{'purl':'RS02MediaMid.mp3?vkey=test'}],'sip':['https://evil.test/','https://sjy.stream.qqmusic.qq.com/']}}}
        with patch('music_preview.json_get',side_effect=[detail,result]):
            self.assertEqual(preview_url('SongMid'),'https://sjy.stream.qqmusic.qq.com/RS02MediaMid.mp3?vkey=test')
        result['req_0']['data']['midurlinfo'][0]['purl']='M500MediaMid.mp3'
        with patch('music_preview.json_get',side_effect=[detail,result]):self.assertIsNone(preview_url('SongMid'))
        with patch('music_preview.json_get',return_value={'code':0,'data':[{'file':{}}]}):self.assertIsNone(preview_url('SongMid'))

    def test_upload_voice_and_cleanup_after_ack_or_failure(self):
        base=ROOT/'build/qqbot-tests';base.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as tmp:
            p=MusicPreview(Path(tmp)/'audio')
            def prepare(mid,directory):
                path=directory/'voice.mp3';path.write_bytes(b'ID3synthetic');return path
            p.prepare=prepare
            async def run(fail=False):
                calls=[]
                async def request(endpoint,payload):
                    calls.append((endpoint,payload))
                    if fail:raise TimeoutError('synthetic')
                    return {'file_info':'fixture'} if endpoint=='files' else {'id':'fixture'}
                self.assertEqual(await p.send(request,{'mid':'SongMid'},'message'),not fail)
                self.assertFalse(list(p.root.iterdir()))
                self.assertEqual(calls[0][1]['file_type'],3)
                self.assertFalse(calls[0][1]['srv_send_msg'])
                if not fail:self.assertEqual(calls[1][1]['msg_seq'],2)
            asyncio.run(run());asyncio.run(run(True))

    def test_missing_trial_quiet_and_cancel_leave_no_audio(self):
        base=ROOT/'build/qqbot-tests';base.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as tmp:
            p=MusicPreview(Path(tmp)/'audio');p.prepare=lambda *a:None
            async def request(*a):raise AssertionError('unexpected upload')
            self.assertFalse(asyncio.run(p.send(request,{'mid':'SongMid'})))
            self.assertFalse(asyncio.run(p.send(request,{'mid':'SongMid'},allowed=lambda:False)))
            self.assertFalse(list(p.root.iterdir()))
            def prepare(mid,directory):
                path=directory/'voice.mp3';path.write_bytes(b'ID3synthetic');return path
            p.prepare=prepare
            async def cancel():
                entered=asyncio.Event()
                async def pending(*a):entered.set();await asyncio.Event().wait()
                task=asyncio.create_task(p.send(pending,{'mid':'SongMid'}));await entered.wait();task.cancel()
                with self.assertRaises(asyncio.CancelledError):await task
                self.assertFalse(list(p.root.iterdir()))
            asyncio.run(cancel())

if __name__=='__main__':unittest.main()
