import base64
import hashlib
from io import BytesIO
import json
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server/qqbot'))
from PIL import Image
from player_avatar import load
from public_artwork import publish
from publish_catalogue import atomic


class CloudAppearanceTests(unittest.TestCase):
    def setUp(self):
        build = Path(__file__).resolve().parents[1] / 'build/appearance-tests'
        build.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=build)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def test_avatar_identity_digest_and_removal(self):
        buffer = BytesIO(); Image.new('RGB', (384, 384), 'red').save(buffer, format='JPEG')
        data = buffer.getvalue()
        path = self.root / '10001/player-avatar.json'; path.parent.mkdir()
        doc = dict(schema=1, qq='10001', server='fixture', image=base64.b64encode(data).decode(), sha256=hashlib.sha256(data).hexdigest())
        path.write_text(json.dumps(doc))
        self.assertEqual(load(self.root, '10001', 'fixture'), data)
        self.assertIsNone(load(self.root, '10001', 'other-server'))
        self.assertIsNone(load(self.root, '../10001', 'fixture'))
        doc['sha256'] = '0'*64; path.write_text(json.dumps(doc))
        self.assertIsNone(load(self.root, '10001', 'fixture'))
        doc['image'] = ''; doc['sha256'] = hashlib.sha256(b'').hexdigest(); path.write_text(json.dumps(doc))
        self.assertIsNone(load(self.root, '10001', 'fixture'))

    def test_same_cid_changed_artwork_uses_new_content_address(self):
        target = self.root / 'bot/catalog.json'; thumbs = target.parent / 'thumbs'; thumbs.mkdir(parents=True)
        digest = hashlib.sha256(b'charts/song/bg.jpg').hexdigest()
        chart = dict(cid=1, server_hash='current-chart', background='charts/song/bg.jpg')
        img = thumbs / (digest + '.jpg'); Image.new('RGB', (20, 20), 'red').save(img)
        publish(dict(server='fixture', charts=[chart]), target, atomic, self.root)
        index = self.root / '_song_art/v2/index.json'; old = json.loads(index.read_text())['charts']['1']
        self.assertEqual(old['server_hash'], 'current-chart')
        Image.new('RGB', (20, 20), 'blue').save(img)
        publish(dict(server='fixture', charts=[chart]), target, atomic, self.root)
        new = json.loads(index.read_text())['charts']['1']
        self.assertNotEqual(old['image'], new['image'])
        actual = index.parent / (new['image'] + '.jpg')
        self.assertEqual(hashlib.sha256(actual.read_bytes()).hexdigest(), new['image'])
        publish(dict(server='fixture', charts=[]), target, atomic, self.root)
        self.assertEqual(json.loads(index.read_text())['charts'], {})

    def test_song_allowlist_preserves_other_hidden_creators(self):
        from publish_catalogue import public_catalogue
        rows = [dict(cid=604386, title='We Luv Lama', creator='Asura夜语'),
                dict(cid=7, title='Another', creator='Asura夜语'),
                dict(cid=604386, title='We Luv Lama', creator='imddesigner')]
        visible = [r['search_visible'] for r in public_catalogue(dict(server='fixture', charts=rows))['charts']]
        self.assertEqual(visible, [True, False, False])


if __name__ == '__main__': unittest.main()
