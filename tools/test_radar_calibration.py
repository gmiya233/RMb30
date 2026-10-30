import json
from pathlib import Path
import unittest

from calibrate_chart_radar import CALIBRATED, SCALE, calibrated_values


class OfficialCalibrationTests(unittest.TestCase):
    def test_reference_targets_and_source_provenance(self):
        doc = json.loads((Path(__file__).resolve().parents[1] / 'android/assets/chart-radar.json').read_text(encoding='utf-8'))
        self.assertEqual(doc['scale'], SCALE)
        profiles = doc['calibration']['profiles']
        self.assertEqual(set(profiles), {'4', '5', '6'})
        for profile in profiles.values():
            self.assertEqual(len(profile['source_sha256']), 64)
            for axis, index in CALIBRATED.items():
                self.assertAlmostEqual(profile['values'][index], 90)
                self.assertAlmostEqual(profile['raw'][axis] / profile['anchors'][axis] * 100, 90)
            self.assertEqual(profile['values'][1], 0)
            self.assertEqual(profile['values'][3], 0)

    def test_ratio_scale_preserves_uncalibrated_axes_without_capping(self):
        profile = {'anchors': {'notes': 100, 'peak': 10, 'slide': 2}}
        result = calibrated_values({'notes': 150, 'peak': 5, 'slide': 0}, profile, [1, 23, 2, 17, 45, 3])
        self.assertEqual(result, [150, 23, 50, 17, 45, 0])

    def test_invalid_reference_anchor_rejected(self):
        for invalid in (0, -1, float('nan'), float('inf')):
            with self.assertRaises(ValueError):
                calibrated_values({'notes': 1, 'peak': 1, 'slide': 1}, {'anchors': {'notes': invalid}}, [0] * 6)

    def test_speed_reference_anchor_and_unchanged_unreferenced_keys(self):
        doc = json.loads((Path(__file__).resolve().parents[1] / 'android/assets/chart-radar.json').read_text(encoding='utf-8'))
        calibration = doc['calibration']
        reference = calibration['speed_reference']
        self.assertEqual(reference['keys'], 6)
        self.assertEqual(reference['raw']['speed_events'], 77)
        self.assertEqual(reference['raw']['speed_channels'], {'scroll': 77})
        self.assertAlmostEqual(reference['raw']['speed'] / reference['anchor'] * 100, 90)
        self.assertEqual(calibration['speed_uncalibrated_keys'], [4, 5])
        for keys in ('4', '5'):
            self.assertNotIn('speed', calibration['profiles'][keys]['anchors'])
        for row in doc['charts']:
            if row['keys'] == 6:
                self.assertAlmostEqual(row['values'][3], row['raw_metrics']['speed'] / reference['anchor'] * 100)

    def test_speed_scale_preserves_true_zero_and_allows_overflow(self):
        profile = {'anchors': {'notes': 100, 'peak': 10, 'slide': 2, 'speed': 20}}
        stats = {'notes': 1, 'peak': 1, 'slide': 1, 'speed': 40}
        self.assertEqual(calibrated_values(stats, profile, [0] * 6)[3], 200)
        stats['speed'] = 0
        self.assertEqual(calibrated_values(stats, profile, [0] * 6)[3], 0)

    def test_all_mx_hold_references_equal_110(self):
        doc = json.loads((Path(__file__).resolve().parents[1] / 'android/assets/chart-radar.json').read_text(encoding='utf-8'))
        calibration = doc['calibration']
        self.assertEqual(calibration['uncalibrated_axes'], ['chord'])
        references = calibration['hold_references']['profiles']
        self.assertEqual(set(references), {'4', '5', '6'})
        for keys, reference in references.items():
            self.assertEqual(reference['difficulty'], 'MX')
            self.assertEqual(reference['keys'], int(keys))
            self.assertEqual(reference['target'], 110)
            self.assertGreater(reference['raw']['hold'], 0)
            self.assertAlmostEqual(reference['raw']['hold'], {'4': 2.462375, '5': 3.000625, '6': 3.9835}[keys])
            self.assertAlmostEqual(reference['raw']['hold'] / reference['anchor'] * 100, 110)
        for row in doc['charts']:
            self.assertAlmostEqual(row['values'][4], row['raw_metrics']['hold'] / references[str(row['keys'])]['anchor'] * 100)


if __name__ == '__main__':
    unittest.main()
