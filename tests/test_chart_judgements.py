import json
from pathlib import Path
import struct
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'server'))
from chart_judgements import count_imd,count_mc

class CapacityTests(unittest.TestCase):
    def test_imd_taps_holds_and_chain(self):
        rows=[(0,0,0,0,0),(1,0,500,1,1),(2,0,1000,0,1000),
              (0x62,0,2000,0,500),(0xa1,0,2500,0,1)]
        raw=struct.pack('<IIId',0,1,0,120)+b'\x03\x03'+struct.pack('<I',len(rows))+b''.join(struct.pack('<BBIBi',*r) for r in rows)
        self.assertEqual(count_imd(raw),2+9+5+1)
        with self.assertRaises(ValueError):count_imd(raw[:-1])

    def test_mc_no_sound_count_and_real_time_hold_duration(self):
        d={'meta':{'mode':7},'time':[{'beat':[0,0,1],'bpm':120}],
           'note':[{'beat':[0,0,1],'sound':'x'}, {'beat':[0,0,1],'x':32},
                   {'beat':[1,0,1],'x':32,'seg':[{'beat':[2,0,1],'x':0}]}]}
        self.assertEqual(count_mc(json.dumps(d)),10)
        # The RM VI tick calculation uses initial BPM and the actual interval across the BPM change.
        d['time'].append({'beat':[2,0,1],'bpm':240})
        self.assertEqual(count_mc(json.dumps(d)),8)

if __name__=='__main__':unittest.main()
