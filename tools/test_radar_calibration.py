import json
import math
from pathlib import Path
import unittest
from calibrate_chart_radar import calibrated_values
from analyze_chart_radar import slide_strength

ROOT=Path(__file__).resolve().parents[1]

class CurrentCalibrationTests(unittest.TestCase):
    def setUp(self):
        self.doc=json.loads((ROOT/'android/assets/chart-radar.json').read_text(encoding='utf-8'))
        self.rules=json.loads((ROOT/'server/qqbot/radar-rules.json').read_text(encoding='utf-8'))

    def test_all_rows_match_current_published_anchors(self):
        self.assertEqual(self.doc['algorithm'],self.rules['algorithm'])
        self.assertEqual(self.doc['scale'],self.rules['scale'])
        self.assertGreater(len(self.doc['charts']),300)
        for r in self.doc['charts']:
            self.assertEqual(len(r['source_sha256']),64)
            for i,axis in enumerate(self.rules['axes']):
                self.assertAlmostEqual(r['values'][i],r['raw_metrics'][axis]*100/self.rules['anchors'][str(r['keys'])][axis])
                self.assertTrue(math.isfinite(r['values'][i]))
            if r['source_format']=='IMD':self.assertEqual(r['values'][3],0)

    def test_ragnarok_100_and_astronomas_real_time_reference(self):
        rows={r['cid']:r for r in self.doc['charts']}
        self.assertEqual(rows[604435]['raw_metrics']['notes'],3885)
        self.assertEqual(rows[604435]['values'][0],100)
        self.assertAlmostEqual(rows[604398]['values'][5],slide_strength(20.75,3049,332.9580000000015)/self.rules['anchors']['4']['slide']*100)
        self.assertAlmostEqual(rows[604401]['values'][5],slide_strength(24,1059,117.02597128378379)/self.rules['anchors']['4']['slide']*100)
        self.assertAlmostEqual((rows[604398]['values'][5]+rows[604401]['values'][5])/2,100)
        self.assertEqual(rows[604332]['values'][3],90)
        for anchors in self.rules['anchors'].values():
            self.assertEqual(anchors['notes'],3885)
            self.assertAlmostEqual(anchors['slide'],(slide_strength(20.75,3049,332.9580000000015)+slide_strength(24,1059,117.02597128378379))/2)

    def test_ratio_scale_does_not_cap(self):
        result=calibrated_values({'notes':150,'peak':5,'slide':0},{'anchors':{'notes':100,'peak':10,'slide':2}},[1,23,2,17,45,3])
        self.assertEqual(result,[150,23,50,17,45,0])

    def test_invalid_anchor(self):
        for value in (0,-1,float('nan'),float('inf')):
            with self.assertRaises(ValueError):calibrated_values({'notes':1},{'anchors':{'notes':value}},[0]*6)

if __name__=='__main__':unittest.main()
