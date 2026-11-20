import asyncio
import hashlib
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'server/qqbot'))
from chart_audio import ChartAudio


class ChartAudioTests(unittest.TestCase):
    def test_read_chart_audio_offset_identity_and_no_source_deletion(self):
        base=ROOT/'build/qqbot-tests';base.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=base) as tmp:
            root=Path(tmp);play=root/'playback';play.mkdir()
            data=b'ID3synthetic-chart-audio';digest=hashlib.sha256(data).hexdigest();source=play/(digest+'.mp3');source.write_bytes(data)
            doc={'schema':1,'server':'server','cid':1,'server_hash':'hash','audio':{'file':source.name,'sha256':digest},'chart':{'notes':[[8]],'audio_start':2}}
            manifest=play/'1.json';manifest.write_text(json.dumps(doc))
            audio=ChartAudio(root/'temp',SimpleNamespace(catalogue=lambda:('server',{1:{'server_hash':'hash'}})),play)
            self.assertEqual(audio.resolve([(1,'hash')]),(source,6))
            self.assertIsNone(audio.resolve([(1,'stale')]))
            def transcode(src,target,start):
                self.assertEqual(src,source);self.assertEqual(start,6);target.write_bytes(b'ID3preview');return target
            audio.transcode=transcode
            async def request(endpoint,payload):
                if endpoint=='files':return {'file_info':'fixture'}
                self.assertEqual(payload['msg_seq'],3);return {'id':'fixture'}
            self.assertTrue(asyncio.run(audio.send(request,[(1,'hash')],'message',sequence=3)))
            self.assertFalse(list(audio.root.iterdir()));self.assertEqual(source.read_bytes(),data)
            source.write_bytes(b'changed');self.assertIsNone(audio.resolve([(1,'hash')]))
            source.write_bytes(data);doc['audio']['file']='../../private.mp3';manifest.write_text(json.dumps(doc));self.assertIsNone(audio.resolve([(1,'hash')]))

    def test_song_card_attaches_audio_only_to_recommendations(self):
        from song_buttons import SongCard
        card=SongCard(b'image',[{'cid':1,'version':'HD'}])
        self.assertFalse(hasattr(card,'preview_charts'))

if __name__=='__main__':unittest.main()
