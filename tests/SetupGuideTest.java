import cn.gmiya.malodylink.SetupGuide;

public final class SetupGuideTest {
    public static void main(String[] args){
        for(int mask=0;mask<16;mask++){
            boolean unlocked=(mask&1)!=0,master=(mask&2)!=0,wireless=(mask&4)!=0,paired=(mask&8)!=0;
            int expected=!unlocked?0:!master||!wireless?1:paired?3:2;
            if(SetupGuide.stage(unlocked,master,wireless,paired)!=expected)throw new AssertionError("setup state "+mask);
        }
        System.out.println("PASS: all prerequisite/pairing states select only the required step");
    }
}
