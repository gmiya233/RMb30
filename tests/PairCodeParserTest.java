import cn.gmiya.malodylink.PairCodeParser;
import java.util.*;

public final class PairCodeParserTest {
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);System.out.println("PASS: "+name);}
    public static void main(String[] args){
        check("123456".equals(PairCodeParser.extract(Arrays.asList("WLAN 配对码","123456","IP 地址和端口"),false)),"localized wireless pairing label accepts one code");
        check("123456".equals(PairCodeParser.extract(Arrays.asList("Wi-Fi pairing code","123 456"),false)),"grouped digits supported");
        check(PairCodeParser.extract(Arrays.asList("验证码","123456"),false)==null,"ordinary verification code rejected");
        check(PairCodeParser.extract(Arrays.asList("使用配对码配对设备","123456"),false)==null,"settings preference is not a pairing code dialog");
        check(PairCodeParser.extract(Arrays.asList("WLAN配对码","123456","654321"),false)==null,"ambiguous codes rejected");
        check(PairCodeParser.extract(Arrays.asList("WLAN配对码","12345","1234567","192.168.1.1:34567"),false)==null,"ports and invalid lengths rejected");
        check("012345".equals(PairCodeParser.extract(Arrays.asList("012345"),true)),"known pairing code resource ID supports leading zero");
        check(PairCodeParser.settingsPackage("com.android.settings")&&!PairCodeParser.settingsPackage("com.tencent.mobileqq")&&!PairCodeParser.settingsPackage("com.android.settings.fake"),"only exact system settings packages allowed");
    }
}
