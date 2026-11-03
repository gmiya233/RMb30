package cn.gmiya.malodylink;

/** Music time drives progress; reference-calibrated perspective drives screen motion. */
public final class GameplayMotion {
 public static final double SKIN_FAR_SCALE=25.9/210.0;
 // Visible viewport calibrated against the supplied 1920x864 RM screenshot.
 public static final double FAR_SCALE=0.20666666666666667;
 public static final double TOP=.015, JUDGE=.735, BOTTOM=.846, NEAR_HALF=.3125;
 public static final double LOGICAL_LANE_SPAN=1680;
 public static double approachForSpeed(double speed){
  if(!Double.isFinite(speed)||speed<=0)throw new IllegalArgumentException("Invalid playback speed");
  return 1.176/speed;
 }
 // Kept for offline comparisons against the previous screen-linear renderer.
 public static double linearY(double remaining,double approach,double height){
  if(!Double.isFinite(remaining)||!Double.isFinite(approach)||!Double.isFinite(height)||approach<=0||height<=0)throw new IllegalArgumentException("Invalid playback geometry");
  return height*JUDGE-remaining/approach*height*(JUDGE-TOP);
 }
 // Approved visual model: RM VI's cubic depth over the visible half of progress.
 // This is a visual reference approximation, not a recovered engine time mapping.
 public static double y(double remaining,double approach,double height){
  double linear=linearY(remaining,approach,height);
  double depth=(linear/height-TOP)/(JUDGE-TOP);
  double p=.5+.5*depth;
  return height*(TOP+(JUDGE-TOP)*(p*p*p-.125)/.875);
 }
 public static double scale(double y,double height){return FAR_SCALE+(1-FAR_SCALE)*(y/height-TOP)/(JUDGE-TOP);}
 public static double halfWidth(double y,double width,double height){return width*NEAR_HALF*scale(y,height);}
 public static double noteWidth(int keys,double y,double width,double height){
  double logical=keys==4?406:keys==5?337.8:290;
  return 2*halfWidth(y,width,height)*logical/LOGICAL_LANE_SPAN;
 }
 public static double halfNoteHeight(double height){return Math.max(4,height*.022);}
 public static double halfNoteHeight(int keys,double y,double width,double height){double logical=keys==4?406:keys==5?337.8:290;return Math.max(2,noteWidth(keys,y,width,height)*116/logical/2);}
}
