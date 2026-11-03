package cn.gmiya.malodylink;

/** Seconds drive vertical motion; RM VI scale drives horizontal perspective only. */
public final class GameplayMotion {
 public static final double SKIN_FAR_SCALE=25.9/210.0;
 // Visible viewport calibrated against the supplied 1920x864 RM screenshot.
 public static final double FAR_SCALE=0.20666666666666667;
 public static final double TOP=.015, JUDGE=.735, BOTTOM=.846, NEAR_HALF=.3125;
 public static final double LOGICAL_LANE_SPAN=1680;
 public static double y(double remaining,double approach,double height){
  if(!Double.isFinite(remaining)||!Double.isFinite(approach)||approach<=0||height<=0)throw new IllegalArgumentException("Invalid playback geometry");
  return height*JUDGE-remaining/approach*height*(JUDGE-TOP);
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
