import cn.gmiya.malodylink.GameplayMotion;
public final class GameplayMotionTest {
 static void near(double a,double b,String label){if(Math.abs(a-b)>1e-7)throw new AssertionError(label+": "+a+" vs "+b);}
 public static void main(String[] args){
  if(args.length>0&&(args[0].equals("--frames")||args[0].equals("--reference-frames"))){boolean reference=args[0].equals("--reference-frames");double approach=reference?GameplayMotion.approachForSpeed(2):1;System.out.println("frame,lane,y,width,halfheight");for(int f=0;f<180;f++){double now=f/30.0;for(int n=0;n<80;n++){double remaining=1+n*.125-now;if(remaining<0||remaining>approach)continue;double y=reference?GameplayMotion.y(remaining,approach,576):GameplayMotion.linearY(remaining,approach,576);System.out.println(f+","+(n%6)+","+y+","+GameplayMotion.noteWidth(6,y,1280,576)+","+GameplayMotion.halfNoteHeight(6,y,1280,576));}}return;}

  near(GameplayMotion.approachForSpeed(2),.588,"approved default visible duration");
  near(GameplayMotion.approachForSpeed(4),GameplayMotion.approachForSpeed(2)/2,"flow adjusts duration");
  for(double h:new double[]{720,1080,2400})for(double speed:new double[]{.5,1,2,6,12}){
   double approach=GameplayMotion.approachForSpeed(speed);
   near(GameplayMotion.y(approach,approach,h),h*GameplayMotion.TOP,"exact visible entry");
   double previous=GameplayMotion.y(approach,approach,h),previousStep=0;
   for(int i=1;i<=100;i++){
    double u=i/100.0,next=GameplayMotion.y(approach*(1-u),approach,h),step=next-previous;
    if(step<=previousStep)throw new AssertionError("Equal-time spacing must expand toward judgement");
    near(next/h,GameplayMotion.y(1-u,1,1),"same trajectory across resolutions and flows");
    for(int keys:new int[]{4,5,6})if(GameplayMotion.noteWidth(keys,next,h*20/9,h)<=0||GameplayMotion.halfNoteHeight(keys,next,h*20/9,h)<=0)throw new AssertionError("Positive note geometry");
    previous=next;previousStep=step;
   }
   double upper=GameplayMotion.y(approach*.8,approach,h)-GameplayMotion.y(approach*.9,approach,h);
   double lower=GameplayMotion.y(approach*.1,approach,h)-GameplayMotion.y(approach*.2,approach,h);
   if(lower<=upper*2)throw new AssertionError("Reference near-end expansion was lost");
   near(GameplayMotion.y(0,approach,h),h*GameplayMotion.JUDGE,"exact judgement-line crossing");
  }
  near(GameplayMotion.halfWidth(720*GameplayMotion.TOP,1280,720)/GameplayMotion.halfWidth(720*GameplayMotion.JUDGE,1280,720),GameplayMotion.FAR_SCALE,"reference visible far/near scale");
  near(GameplayMotion.noteWidth(4,720*GameplayMotion.JUDGE,1280,720)/(1280*(2*GameplayMotion.NEAR_HALF)/4),406.0/420,"4K skin width");
  near(GameplayMotion.noteWidth(6,720*GameplayMotion.JUDGE,1280,720)/(1280*(2*GameplayMotion.NEAR_HALF)/6),290.0/280,"6K skin width");
  near(GameplayMotion.halfNoteHeight(6,576*GameplayMotion.JUDGE,1280,576)*2/GameplayMotion.noteWidth(6,576*GameplayMotion.JUDGE,1280,576),116.0/290,"original texture aspect ratio");
  System.out.println("PASS: 1500 perspective samples at 0.5x-12x; expanding gaps; hit alignment; 4K/5K/6K note geometry");
 }
}
