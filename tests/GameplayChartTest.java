import cn.gmiya.malodylink.GameplayChart;import org.json.*;import java.nio.*;import java.nio.charset.StandardCharsets;
public class GameplayChartTest {
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
 String raw="{\"meta\":{\"mode\":7},\"time\":[{\"beat\":[0,0,1],\"bpm\":120},{\"beat\":[4,0,1],\"bpm\":240}],\"note\":[{\"beat\":[0,0,1],\"sound\":\"song.ogg\",\"offset\":269},{\"beat\":[2,0,1],\"x\":32,\"w\":60,\"seg\":[{\"beat\":[4,0,1],\"x\":64,\"dir\":2}]}]}";
 GameplayChart g=GameplayChart.mc(raw.getBytes(StandardCharsets.UTF_8),4);check(g.notes.size()==1);check(Math.abs(g.audioStart-.269)<1e-9);check(g.notes.get(0).at==1);check(g.notes.get(0).end==2.5);check(g.notes.get(0).points.get(0)[1]==96);check(g.notes.get(0).tail==2);
 JSONObject stop=new JSONObject(raw);stop.getJSONArray("time").getJSONObject(1).put("delay",1);try{GameplayChart.mc(stop.toString().getBytes(StandardCharsets.UTF_8),4);throw new AssertionError("stop accepted");}catch(java.io.IOException expected){}
 ByteBuffer b=ByteBuffer.allocate(37).order(ByteOrder.LITTLE_ENDIAN);b.putInt(1000).putInt(1).putInt(0).putDouble(120).put((byte)3).put((byte)3).putInt(1).put((byte)2).put((byte)0).putInt(1000).put((byte)1).putInt(500);GameplayChart imd=GameplayChart.imd(b.array(),4);check(imd.notes.get(0).at==1);check(imd.notes.get(0).end==1.5);check(imd.notes.get(0).x==96);check(imd.audioStart==0);
 JSONObject speed=new JSONObject(raw);speed.put("effect",new JSONArray("[{\"beat\":[4,0,1],\"scroll\":2}]"));GameplayChart sg=GameplayChart.mc(speed.toString().getBytes(StandardCharsets.UTF_8),4);check(Math.abs(sg.travel(1)-sg.travel(0)-1)<1e-8);check(Math.abs(sg.travel(3)-sg.travel(2)-2)<1e-8);
 System.out.println("PASS: BPM timing, MC audio offset, relative slide path, unsupported stops, IMD hold timing");
 }
}