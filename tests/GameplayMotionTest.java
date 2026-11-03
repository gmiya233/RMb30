import cn.gmiya.malodylink.GameplayMotion;
public final class GameplayMotionTest {
 static void near(double a,double b,String label){if(Math.abs(a-b)>1e-7)throw new AssertionError(label+": "+a+" vs "+b);}
 public static void main(String[] args){
  if(args.length>0&&args[0].equals("--frames")){System.out.println("frame,lane,y,width,halfheight");for(int f=0;f<180;f++){double now=f/30.0;for(int n=0;n<80;n++){double remaining=1+n*.125-now;if(remaining<0||remaining>1)continue;double y=GameplayMotion.y(remaining,1,576);System.out.println(f+","+(n%6)+","+y+","+GameplayMotion.noteWidth(6,y,1280,576)+","+GameplayMotion.halfNoteHeight(6,y,1280,576));}}return;}

  for(double h:new double[]{720,1080,2400})for(double speed:new double[]{.5,1,2,6,12}){
   double approach=2/speed,hit=10,dt=1.0/120;
   double expected=h*(GameplayMotion.JUDGE-GameplayMotion.TOP)/approach*dt;
   for(int i=0;i<100;i++){double now=hit-approach+(approach-.2)*i/100;double a=GameplayMotion.y(hit-now,approach,h),b=GameplayMotion.y(hit-now-dt,approach,h);near(b-a,expected,"constant screen velocity");
    // Equal timestamp spacing retains the same pixel spacing at the top and bottom.
    near(GameplayMotion.y(hit+.08-now,approach,h)-a,-h*(GameplayMotion.JUDGE-GameplayMotion.TOP)/approach*.08,"no gap compression");}
   near(GameplayMotion.y(0,approach,h),h*GameplayMotion.JUDGE,"exact judgement-line crossing");
  }
  near(GameplayMotion.halfWidth(720*GameplayMotion.TOP,1280,720)/GameplayMotion.halfWidth(720*GameplayMotion.JUDGE,1280,720),GameplayMotion.FAR_SCALE,"reference visible far/near scale");
  near(GameplayMotion.noteWidth(4,720*GameplayMotion.JUDGE,1280,720)/(1280*(2*GameplayMotion.NEAR_HALF)/4),406.0/420,"4K skin width");
  near(GameplayMotion.noteWidth(6,720*GameplayMotion.JUDGE,1280,720)/(1280*(2*GameplayMotion.NEAR_HALF)/6),290.0/280,"6K skin width");
  near(GameplayMotion.halfNoteHeight(6,576*GameplayMotion.JUDGE,1280,576)*2/GameplayMotion.noteWidth(6,576*GameplayMotion.JUDGE,1280,576),116.0/290,"original texture aspect ratio");
  System.out.println("PASS: 1500 trajectory samples at 0.5x-12x; fixed gaps; hit alignment; RM VI perspective and note widths");
 }
}
