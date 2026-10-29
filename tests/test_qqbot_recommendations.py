import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server/qqbot'))
from recommendations import Recommendations,INTERVAL

class RecommendationTests(unittest.TestCase):
    def setUp(self):
        root=Path(__file__).resolve().parents[1]/'build/qqbot-tests';root.mkdir(parents=True,exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(dir=root);self.addCleanup(self.temp.cleanup)
        self.now=100000.
        class Queries:
            def catalogue(q):
                return 'fixture',{1:dict(cid=1,sid=10,title='Visible',artworks=[]),2:dict(cid=2,sid=10,title='Visible'),3:dict(cid=3,sid=11,title='Hidden',search_visible=False),4:dict(cid=4,sid=12,title='Other')}
            def board(q,cid): return [],'fixture'
        self.args=(Queries(),'fixture',[],Path(self.temp.name)/'rec.sqlite3')
        self.rec=Recommendations(*self.args,clock=lambda:self.now)

    def test_schedule_is_opt_in_persistent_and_no_catchup_flood(self):
        self.assertEqual(self.rec.due(set()),[])
        self.rec.enable('group-a');self.rec.enable('group-b')
        self.now+=INTERVAL-1;self.assertEqual(self.rec.due(set()),[])
        self.now+=1;self.assertEqual(self.rec.due({'group-a'}),['group-a'])
        self.assertEqual(self.rec.due({'group-a'}),[])
        restarted=Recommendations(*self.args,clock=lambda:self.now)
        self.assertEqual(restarted.due({'group-a'}),[])
        self.now+=INTERVAL*5;self.assertEqual(restarted.due({'group-a'}),['group-a'])
        self.assertEqual(restarted.due({'group-a'}),[])
        restarted.disable('group-a');self.now+=INTERVAL
        self.assertEqual(restarted.due({'group-a'}),[])
        self.assertIn('未开启',restarted.status('group-a'))
        self.assertIn('下一次',restarted.status('group-b'))

    def test_card_contains_same_song_difficulties_and_filters_hidden(self):
        rendered=[]
        def draw(rows,*args,**kwargs):
            rendered.append(rows);return b'PNG fixture'
        with patch('recommendations.render',side_effect=draw),patch('recommendations.artwork',return_value=None):
            self.rec.card('a');self.rec.card('a')
        self.assertEqual({r[0]['sid'] for r in rendered},{10,12})
        self.assertEqual(sorted(len(r) for r in rendered),[1,2])
        self.assertNotIn(3,[row['cid'] for rows in rendered for row in rows])
        self.assertEqual(len(self.rec.history('a')),2)
        self.assertEqual(self.rec.history('b'),[])

if __name__=='__main__':unittest.main()
