from pathlib import Path
import sys,unittest,tempfile
ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT/'tools/python-libs'),str(ROOT/'tools')]
from render_gameplay_video import ScrollTrack,Stage
from PIL import Image

class ScrollTests(unittest.TestCase):
 def test_constant_speed_and_event_boundary_continuity(self):
  base=ScrollTrack([])
  self.assertAlmostEqual(base.travel(5)-base.travel(2),3)
  track=ScrollTrack([[1,2],[2,.5],[3,1]])
  self.assertAlmostEqual(track.travel(2)-track.travel(0),3)
  self.assertAlmostEqual(track.travel(3)-track.travel(2),.5)
  self.assertLess(abs(track.travel(2+1e-7)-track.travel(2-1e-7)),1e-5)
 def test_pause_reverse_and_extrema(self):
  track=ScrollTrack([[1,0],[2,-2],[3,1]])
  self.assertEqual(track.travel(1),track.travel(2))
  self.assertAlmostEqual(track.travel(3)-track.travel(2),-2)
  low,high=track.span(0,4)
  self.assertAlmostEqual(high-low,2)
  with self.assertRaises(ValueError):ScrollTrack([[0,float('nan')]])
 def test_slow_note_is_visible_beyond_old_time_window(self):
  directory=ROOT/'build/video-scroll-tests';directory.mkdir(parents=True,exist_ok=True)
  with tempfile.TemporaryDirectory(dir=directory) as temporary:
   cover=Path(temporary)/'cover.jpg';Image.new('RGB',(1280,576),'#123456').save(cover)
   chart=dict(keys=4,scroll=[[0,.1]],notes=[[5,32,60,0,0,[]]],effects=False)
   stage=Stage(chart,cover,'Synthetic scroll')
   self.assertGreater(stage.y(5,0),8.64)
   self.assertLess(stage.y(5,0),423.36)
   self.assertAlmostEqual(stage.y(5,5),512.0)
   self.assertAlmostEqual(stage.y(5+stage.approach/.1,5),stage.TOP_Y)
   self.assertEqual(stage.frame(0,6).size,(1280,576))
   for keys in (4,5,6):
    chart['keys']=keys
    current=Stage(chart,cover,'Judge alignment')
    self.assertAlmostEqual(current.y(5,5),current.JUDGE_Y)
    frame=current.frame(5,6)
    lane=int(32*keys/256)
    center=round(current.x((lane+.5)*256/keys,current.JUDGE_Y))
    self.assertEqual(frame.getpixel((center,514)),(140,255,255))

if __name__=='__main__':unittest.main()
