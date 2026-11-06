from pathlib import Path
import sys,unittest
ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT/'tools/beat-extra'),str(ROOT/'tools/tempo-libs'),str(ROOT/'tools')]
import numpy as np
from tempo_grid import grid_decode,verify_segments

class GridTests(unittest.TestCase):
 def test_audit_preserves_candidates_and_marks_octave_conflict(self):
  primary={'segments':[{'start':0,'end':20,'bpm':120,'uncertain':False}],'changes':[]}
  grid={'segments':[{'start':0,'end':20,'bpm':240,'grid_drift_ms':40}]}
  result=verify_segments(primary,grid)
  self.assertEqual(result['segments'][0]['bpm'],120)
  self.assertTrue(result['segments'][0]['uncertain'])
  self.assertIn('拍级／三连音分组存在歧义',result['segments'][0]['review_reasons'])
  self.assertFalse(primary['segments'][0]['uncertain'])
 def fixture(self,parts):
  duration=parts[-1][1];t=np.arange(round(duration*50))/50;beat=np.zeros(len(t));down=np.zeros(len(t));onset=np.zeros(len(t))
  for start,end,bpm in parts:
   for i,at in enumerate(np.arange(start,end,60/bpm)):
    pulse=np.exp(-.5*((t-at)/.018)**2);beat=np.maximum(beat,pulse);onset=np.maximum(onset,pulse)
    if i%4==0:down=np.maximum(down,pulse)
   # Fast subdivisions in the sound are not extra musical quarter notes.
   for at in np.arange(start,end,60/bpm/3):onset=np.maximum(onset,.65*np.exp(-.5*((t-at)/.015)**2))
  return beat,down,onset,duration
 def test_variable_grid_with_triplet_audio(self):
  d=grid_decode(*self.fixture([(0,24,120),(24,48,180),(48,72,150)]))
  self.assertEqual(len(d['changes']),2,d['changes'])
  for s,expected in zip(d['segments'],[120,180,150]):self.assertLess(abs(s['bpm']-expected),1)
  for change,expected in zip(d['changes'],[24,48]):self.assertLess(abs(change['time']-expected),1)
 def test_constant_grid_and_no_evidence(self):
  d=grid_decode(*self.fixture([(0,20,150)]));self.assertEqual(d['changes'],[]);self.assertLess(abs(d['segments'][0]['bpm']-150),1)
  with self.assertRaises(ValueError):grid_decode(np.zeros(1000),np.zeros(1000),np.zeros(1000),20)
if __name__=='__main__':unittest.main()
