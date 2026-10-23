import cn.gmiya.malodylink.SetupGuide;

public final class SetupGuideTest {
    public static void main(String[] args){
        for(int mask=0;mask<16;mask++){
            boolean unlocked=(mask&1)!=0,master=(mask&2)!=0,wireless=(mask&4)!=0,paired=(mask&8)!=0;
            int expected=!unlocked||!master?0:!wireless?1:paired?3:2;
            if(SetupGuide.stage(unlocked,master,wireless,paired)!=expected)throw new AssertionError("setup state "+mask);
        }
        if(SetupGuide.stage(true,false,false,false)!=SetupGuide.DEVELOPER)throw new AssertionError("unlocked entry still needs developer master switch");
        if(SetupGuide.stage(true,false,true,true)!=SetupGuide.DEVELOPER)throw new AssertionError("old pairing/wireless flags cannot bypass disabled developer mode");
        if(SetupGuide.stage(true,true,false,false)!=SetupGuide.WIRELESS)throw new AssertionError("enabled developer mode skips version tapping");
        System.out.println("PASS: all prerequisite/pairing states select only the required step");
    }
}
