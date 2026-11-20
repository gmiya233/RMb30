from pathlib import Path
import sys,unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server/qqbot'))
from level_table import select,dominant

class LevelTableTests(unittest.TestCase):
    def row(self,cid,grade=14,values=None,**extra):
        return dict(cid=cid,sid=1,title='Song',level=grade,version='',radar=dict(values=values or [10,20,30,40,50,60]))|extra
    def test_level_fold_visibility_single_category_and_no_song_dedup(self):
        rows=[self.row(1),self.row(2),self.row(3,15),self.row(4,14.8),self.row(5,search_visible=False),self.row(6,32),self.row(7,14,version='SC14')]
        result=select(rows,'14');self.assertEqual(sorted(r['cid'] for group in result.values() for r in group),[1,2,4,6])
        self.assertEqual(len(result[5]),4)
    def test_axis_order_tie_and_unknown(self):
        self.assertEqual(dominant(self.row(1,values=[0,0,0,100,0,0])),3)
        self.assertEqual(dominant(self.row(1,values=[20]*6)),0)
        self.assertIsNone(dominant(dict(radar=dict(values=[0]*6))))
        self.assertIsNone(dominant(dict(radar=dict(values=[float('nan')]*6))))
        with self.assertRaises(ValueError):select([],'14.8')
        with self.assertRaises(ValueError):select([],'99')

if __name__=='__main__':unittest.main()
