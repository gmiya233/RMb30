package cn.gmiya.malodylink;

/** User-defined converted rating, deliberately separate from Malody official MM. */
public final class RatingRules {
    public static final double[] PRESET={Double.NaN,.78,.85,1.00,1.10,1.20};
    public final double[] multipliers;
    public RatingRules(){multipliers=PRESET.clone();}
    public RatingRules(double[] values){
        if(values.length!=6)throw new IllegalArgumentException("需要 A～E 五个倍率");
        multipliers=values.clone();for(int i=1;i<=5;i++)if(!Double.isFinite(values[i])||values[i]<=0||values[i]>10)throw new IllegalArgumentException("倍率必须在 0～10 之间");
    }
    public static String judgeName(int judge){return judge>=1&&judge<=5?"ABCDE".substring(judge-1,judge)+"判":"判定待核对";}
    /** Malody serializes JudgeLevel as A=0..E=4; UI/rating indices stay 1..5. */
    public static int replayJudge(double raw){return Double.isFinite(raw)&&raw>=0&&raw<=4&&raw==Math.rint(raw)?(int)raw+1:0;}
    public static double accuracyFactor(double accuracy){
        if(!Double.isFinite(accuracy)||accuracy<0||accuracy>100)throw new IllegalArgumentException("准度应在 0～100% 之间");
        if(accuracy>=99.8)return 1.01;
        if(accuracy>=99.5)return 1.00+(accuracy-99.5)/30.0;
        return Math.pow(accuracy/99.5,2);
    }
    public static double normalizedLevel(double raw){
        if(!Double.isFinite(raw)||raw<=0)return Double.NaN;
        if(raw<16)return raw;
        if(raw==16)return 8;
        // User supplied MaV -> RM table. Unlisted values need an explicit rating.
        if(raw>35||raw!=Math.rint(raw))return Double.NaN;
        if(raw<=18)return 9;
        if(raw<=20)return 10;
        if(raw<=23)return 11;
        if(raw<=26)return 12;
        if(raw<=29)return 13;
        if(raw<=32)return 14;
        return 15;
    }
    public double converted(double level,double accuracy,int judge){
        if(!Double.isFinite(level)||level<=0||judge<1||judge>5)return Double.NaN;
        return normalizedLevel(level)*accuracyFactor(accuracy)*multipliers[judge];
    }
    public double rating(double raw,String version,double accuracy,int judge){
        if(judge<1||judge>5)return Double.NaN;
        return ChartDetails.level(raw,version)*accuracyFactor(accuracy)*multipliers[judge];
    }
}
