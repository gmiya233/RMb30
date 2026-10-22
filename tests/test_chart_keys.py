"""Synthetic chart geometry only; no files from devices or servers."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'server'))
from chart_keys import extract, version_keys


class ChartKeysTest(unittest.TestCase):
    def test_regular_grids(self):
        for count in (4, 5, 6):
            chart = {'meta': {'mode': 7, 'version': 'Lv.12'},
                     'note': [{'x': round((lane + .5) * 256 / count) - 1,
                               'w': round(256 / count) - 3}
                              for repeat in range(12) for lane in range(count)]}
            self.assertEqual(extract(chart), (count, 'slide_grid'))
            chart['meta']['version'] = ('6K' if count == 4 else '4K')
            self.assertEqual(extract(chart), (0, 'conflicting_metadata'))

    def test_variable_positions(self):
        chart = {'meta': {'mode': 7}, 'note': [{'x': x, 'w': 20} for x in range(256)]}
        self.assertEqual(extract(chart), (0, 'unknown'))

    def test_native_and_names(self):
        self.assertEqual(extract({'meta': {'mode': 0, 'mode_ext': {'column': 7}}}), (7, 'metadata'))
        self.assertEqual(version_keys('Ver_4k_hd_Lv.13'), 4)
        self.assertEqual(version_keys('Lv.14'), 0)
        self.assertEqual(version_keys('4K 6K'), 0)


if __name__ == '__main__':
    unittest.main()
