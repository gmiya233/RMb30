import unittest

from analyze_chart_radar import analyze, peak_density, slide_points, speed_changes
from render import Chart, Note


class RadarStudyTests(unittest.TestCase):
    def test_user_drawn_examples(self):
        self.assertEqual(slide_points(Note(0, 0, 60, 0, ((0,0),(1,64)))), 2)
        self.assertEqual(slide_points(Note(0, 0, 61, 2, ())), 1)
        self.assertEqual(slide_points(Note(0, 0, 60, 0, ((0,0),(1,64),(2,0)))), 4)

    def test_redundant_points_and_straight_hold(self):
        self.assertEqual(slide_points(Note(0, 0, 60, 0, ((0,0),(0.5,0),(1,0),(1,32),(1,64)))), 2)
        self.assertEqual(slide_points(Note(0, 0, 60, 0, ((0,0),(1,0)))), 0)

    def test_continuous_window_and_boundary(self):
        self.assertEqual(peak_density([3.9,4,4.1,7.8])[1], 4)
        self.assertEqual(peak_density([0,4])[1], 1)

    def test_speed_initial_duplicate_and_coincident_changes(self):
        chart=Chart([],[(0,120),(4,120),(8,180),(12,180)],4,0,16,True)
        doc={'effect':[{'beat':[0,0,1],'scroll':2},{'beat':[4,0,1],'scroll':2},{'beat':[8,0,1],'scroll':3},{'beat':[10,0,1],'hs':2},{'beat':[11,0,1],'jump':20}]}
        count,channels,unknown=speed_changes(chart,doc,0,16)
        self.assertEqual(count,2)
        self.assertEqual(channels,{'bpm':1,'scroll':1,'hs':1})
        self.assertEqual(unknown,0)

    def test_heads_chords_and_straight_holds(self):
        notes=[Note(0,x,60,0,()) for x in (0,64,128)]
        notes += [Note(1,x,60,0,()) for x in (0,64,128,192)]
        notes += [Note(2,0,60,0,((2,0),(4,0))),Note(2,64,60,0,((2,64),(4,128)))]
        result=analyze(Chart(notes,[(0,120)],4,0,4,False),{})
        self.assertEqual((result['notes'],result['chord3'],result['chord4'],result['chord']), (9,1,1,2))
        self.assertEqual(result['hold'],1)
        self.assertEqual(result['slide_points'],2)
        self.assertEqual(result['duration_s'],2)

    def test_imd_speed_zero_other_five_metrics_preserved(self):
        notes=[Note(0,0,60,0,((0,0),(2,64))),Note(4,64,60,0,())]
        chart=Chart(notes,[(0,120),(2,240)],4,0,4,True)
        doc={'effect':[{'beat':[2,0,1],'scroll':2}]}
        mc=analyze(chart,doc)
        self.assertGreater(mc['speed'],0)
        chart.source_format='IMD'
        imd=analyze(chart,doc)
        for key in ('speed','speed_events','unknown_speed_effects'):
            self.assertEqual(imd[key],0)
        self.assertEqual(imd['speed_channels'],{})
        self.assertEqual(imd['speed_status'],'disabled_imd_zero')
        for key in ('notes','chord','peak','hold','slide','duration_s'):
            self.assertEqual(imd[key],mc[key])


if __name__ == '__main__':
    unittest.main()
