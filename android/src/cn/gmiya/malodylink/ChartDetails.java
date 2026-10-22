package cn.gmiya.malodylink;

import java.util.regex.*;

/** User-supplied SC difficulty conversion; independent of raw MaV levels. */
public final class ChartDetails {
    private static final Pattern SC=Pattern.compile("(?i)(?<![A-Z])SC\\s*([1-9][0-9]*)(?![0-9.])");
    public static int sc(String version){try{Matcher m=SC.matcher(version==null?"":version);return m.find()?Integer.parseInt(m.group(1)):0;}catch(Exception e){return 0;}}
    public static double level(double raw,String version){int sc=sc(version);if(sc>0)return sc<=4?12:sc<=13?13:15;return RatingRules.normalizedLevel(raw);}
}
