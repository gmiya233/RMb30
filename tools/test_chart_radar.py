import unittest

from analyze_chart_radar import analyze, peak_density, peak_hold_occupancy, slide_points, slide_point_beats, speed_changes
from render import Chart, Note


class RadarStudyTests(unittest.TestCase):
    def test_user_drawn_examples(self):
        self.assertEqual(slide_points(Note(0, 0, 60, 0, ((0,0),(1,64)))), 2)
        self.assertEqual(slide_points(Note(0, 0, 61, 2, ())), 2)
        self.assertEqual(slide_points(Note(0, 0, 60, 0, ((0,0),(1,64),(2,0)))), 4)

    def test_single_flick_directions_and_plain_tap(self):
        for direction in (2, 8, 4, 16):
            self.assertEqual(slide_points(Note(0, 0, 61, direction, ())), 2)
        self.assertEqual(slide_points(Note(0, 0, 60, 0, ())), 0)

    def test_redundant_points_and_straight_hold(self):
        self.assertEqual(slide_points(Note(0, 0, 60, 0, ((0,0),(0.5,0),(1,0),(1,32),(1,64)))), 2)
        self.assertEqual(slide_points(Note(0, 0, 60, 0, ((0,0),(1,0)))), 0)

    def test_continuous_window_and_boundary(self):
        self.assertEqual(peak_density([3.9,4,4.1,7.8])[1], 4)
        self.assertEqual(peak_density([0,4])[1], 1)

    def test_burst_counts_heads_and_single_flick_points(self):
        chart = Chart([Note(0,0,61,2,()), Note(2,64,60,0,())], [(0,120)], 4, 0, 2, False)
        stats = analyze(chart, {})
        self.assertEqual(stats['peak'], 1)
        self.assertEqual(stats['peak_count'], 4)  # Two heads plus two flick points.
        self.assertEqual((stats['peak_head_count'], stats['peak_slide_count']), (2,2))
        self.assertEqual(stats['head_peak'], .5)

    def test_chain_turns_use_actual_time_and_half_open_window(self):
        chain = Note(0,0,60,0,((0,0),(10,64),(20,0)))
        self.assertEqual(slide_point_beats(chain), [10,10,20,20])
        stats = analyze(Chart([chain], [(0,60)], 4, 0, 20, False), {})
        self.assertEqual(stats['slide_points'], 4)
        self.assertEqual(stats['peak_count'], 2)
        self.assertEqual(stats['peak_start_s'], 10)
        self.assertEqual((stats['peak_head_count'],stats['peak_slide_count']), (0,2))

    def test_point_beats_are_converted_across_bpm_changes(self):
        chain = Note(0,0,60,0,((0,0),(4,64),(8,0)))
        chart = Chart([chain], [(0,60),(4,120)], 4, 0, 8, False)
        stats = analyze(chart, {})
        self.assertEqual(stats['duration_s'], 6)
        self.assertEqual(stats['peak_count'], 4)
        self.assertEqual(stats['peak_start_s'], 4)

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
        self.assertEqual(result['hold_count'],1)
        self.assertEqual(result['hold'],.125)
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

    def test_hold_occupancy_ignores_empty_time_and_counts_overlap(self):
        self.assertEqual(peak_hold_occupancy([],0,30), (0,0))
        self.assertEqual(peak_hold_occupancy([(5,13),(5,13)],0,30), (2,5))
        self.assertEqual(peak_hold_occupancy([(5,5.1)]*4,0,30)[0], 4 * (5.1-5) / 8)

    def test_hold_occupancy_window_can_start_before_a_hold_edge(self):
        value, start = peak_hold_occupancy([(1,3),(8,12)],0,15)
        self.assertEqual(value,.5)
        self.assertEqual(start,4)

    def test_hold_occupancy_long_hold_and_short_chart_fixed_denominator(self):
        self.assertEqual(peak_hold_occupancy([(2,20)],0,20), (1,2))
        self.assertEqual(peak_hold_occupancy([(0,2)],0,2), (.25,0))
        self.assertEqual(peak_hold_occupancy([(0,4),(4,8)],0,8), (1,0))

    def test_hold_occupancy_respects_bpm_and_excludes_slides(self):
        hold = Note(0,0,60,0,((0,0),(8,0)))
        slide = Note(0,64,60,0,((0,64),(8,128)))
        result = analyze(Chart([hold,slide],[(0,60),(4,120)],4,0,8,False),{})
        self.assertEqual(result['hold_count'],1)
        self.assertEqual(result['hold'],.75)  # 6 real seconds / fixed 8s.


if __name__ == '__main__':
    unittest.main()
