import cn.gmiya.malodylink.ChartMatcher;
import cn.gmiya.malodylink.Proto;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class CoreTest {
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);System.out.println("PASS: "+label);}
    public static void main(String[] args)throws Exception {
        String a="{\"meta\":{\"mode\":0,\"song\":{\"title\":\"A\"}},\"note\":[{\"beat\":[0,0,1],\"column\":1}],\"time\":[{\"bpm\":155}]}";
        String cosmetics=a.replace("\"A\"","\"Renamed\"").replace("155}","155.0}");
        check(ChartMatcher.matches(bytes(a),bytes(cosmetics)),"metadata rename and numeric representation preserve gameplay match");
        check(!ChartMatcher.matches(bytes(a),bytes(a.replace("\"column\":1","\"column\":2"))),"same title but changed note is rejected");
        check(!ChartMatcher.matches(bytes(a),bytes(a.replace("155}","156}"))),"tempo change is rejected");
        check(!ChartMatcher.matches(bytes(a),bytes(a.replace("\"mode\":0","\"mode\":7"))),"mode change is rejected");
        check(!ChartMatcher.matches(bytes(a),bytes(a.replace("\"mode\":0","\"mode\":0,\"mode_ext\":{\"key\":6}"))),"mode extension change is rejected");
        boolean truncated=false;try{Proto.decode(new byte[]{10,10,1});}catch(java.io.IOException expected){truncated=true;}
        check(truncated,"malformed binary data is rejected");
        byte[] replay=Files.readAllBytes(Paths.get(args[0]));
        List<Proto.Field> f=Proto.decode(replay);String hash=Proto.replayHash(replay);
        check(hash.length()==32,"real replay chart identity decoded");
        List<Proto.Field> score=Proto.decode((byte[])Proto.get(f,8));
        check(Proto.integer(score,5)==36146,"real sample numeric field 5 agrees with independent Python decoder");
        check(Math.abs(Proto.real(score,11)-11.45035171508789)<0.0000001,"real sample float32 agrees with independent decoder");
        byte[] local=Files.readAllBytes(Paths.get(args[1])),server=Files.readAllBytes(Paths.get(args[2]));
        check(ChartMatcher.matches(local,server),"real local and server chart gameplay match despite metadata differences");
        check(ChartMatcher.md5(local).equals(hash),"replay identity matches exact local chart file MD5");
    }
}
