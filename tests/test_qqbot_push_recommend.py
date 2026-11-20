from pathlib import Path
import sys,unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server/qqbot'))
from push_recommend import recommend,factor

class PushTests(unittest.TestCase):
    def fixture(self):
        charts=[dict(cid=i,sid=i,level=12,version='',keys=4,title='Test') for i in range(1,5)]
        scores=[dict(cid=i,accuracy=98,judge=3,rating=12*factor(98),score=100) for i in range(1,4)]
        doc=dict(old=scores,new=[],rating=sum(s['rating'] for s in scores)/30)
        return charts,doc
    def test_failed_overlevel_does_not_raise_cap_or_switch_keys(self):
        charts,doc=self.fixture()
        charts += [dict(cid=5,sid=5,level=15,version='',keys=4),dict(cid=6,sid=6,level=12,version='',keys=6)]
        s=dict(cid=5,accuracy=50,judge=3,rating=15*factor(50),score=100)
        doc['old'].append(s);doc['rating']+=s['rating']/30
        rows,caps=recommend(charts,doc)
        self.assertEqual(caps[4],12)
        self.assertTrue(rows)
        self.assertTrue(all(r['grade']<=12 and r['keys']==4 for r in rows))
        self.assertTrue(all(r['judge']==3 for r in rows))
    def test_new_song_needs_three_stable_songs_and_deduplicates(self):
        charts,doc=self.fixture();charts.append(dict(charts[3],cid=9))
        rows,_=recommend(charts,doc)
        self.assertEqual(sum(r['chart']['sid']==4 for r in rows),1)
        for row in rows:
            self.assertGreater(row['gain'],0)
            self.assertLessEqual(row['accuracy'],99.8)
        doc['old']=doc['old'][:1];doc['rating']=doc['old'][0]['rating']/30
        rows,_=recommend(charts,doc)
        self.assertTrue(all(r['existing'] for r in rows))
    def test_unknown_keys_preserve_baseline_and_invalid_summary_rejected(self):
        charts,doc=self.fixture();charts[0]['keys']=0
        recommend(charts,doc)
        doc['rating']=99
        with self.assertRaises(ValueError):recommend(charts,doc)

if __name__=='__main__':unittest.main()
