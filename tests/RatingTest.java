import cn.gmiya.malodylink.RatingRules;
import cn.gmiya.malodylink.PosterData;
import cn.gmiya.malodylink.ChartDetails;
import java.util.*;

public class RatingTest {
    private static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);System.out.println("PASS: "+name);}
    private static boolean near(double a,double b){return Math.abs(a-b)<.000000001;}
    private static PosterData.Entry entry(int cid,int sid,String section,double rating){PosterData.Entry e=new PosterData.Entry();e.cid=cid;e.sid=sid;e.section=section;e.converted=rating;e.accuracy=99.5;return e;}
    public static void main(String[] args){
        RatingRules rules=new RatingRules();
        for(int sc=1;sc<=20;sc++){int expected=sc<=4?12:sc<=13?13:15;check(near(rules.rating(Double.NaN,"SC "+sc,99.5,3),expected),"SC "+sc+" maps to "+expected);}
        check(near(rules.rating(14,"[6K-LV.14+] RM SC14",99.5,3),15),"SC mapping takes precedence over numeric catalogue level");
        check(near(rules.rating(32,"SC9",99.8,1),13*1.01*.78),"SC normalized once then accuracy and judge multiplier");
        check(ChartDetails.sc("SC9.5")==0&&ChartDetails.sc("SC0")==0,"invalid SC values not silently rounded");
        check(near(rules.rating(24,"4K LV.24",99.5,3),12),"non-SC MaV conversion preserved");
        PosterData.Board section=new PosterData.Board();section.old.add(entry(1,1,"O",12));section.fresh.add(entry(2,2,"N",15));
        check(near(section.oldRating(),.6)&&near(section.newRating(),1.5)&&near(section.rating(),.9),"section ratings divide by 20/10 and weighted total divides by 30");
        section.old.add(entry(3,3,"O",Double.NaN));check(near(section.oldRating(),.6),"unknown rating contributes zero without changing denominator");
        PosterData.Entry automatic=entry(4,4,"O",13);automatic.level=0;automatic.difficultyVersion="SC9";automatic.manual=true;
        check(near(automatic.displayLevel(),13),"global judge preview retains SC level");automatic.level=12;automatic.levelOverride=true;check(near(automatic.displayLevel(),12),"explicit manual level overrides SC mapping");
        int[] source={17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35};
        int[] target={9,9,10,10,11,11,11,12,12,12,13,13,13,14,14,14,15,15,15};
        for(int n=0;n<source.length;n++)check(near(RatingRules.normalizedLevel(source[n]),target[n]),"MaV "+source[n]+" -> RM "+target[n]);
        check(Double.isNaN(rules.converted(36,99.5,3)),"unlisted level is not extrapolated");
        check(Double.isNaN(RatingRules.normalizedLevel(17.5)),"unlisted fractional MaV level needs rating");
        check(near(rules.converted(16,99.5,3),8),"level 16 is halved at boundary");
        check(near(rules.converted(24,99.8,3),12.12),"level 24 uses normalized 12 before accuracy");
        check(near(rules.converted(32,99.5,3),14),"32 maps to 14 rather than halving");
        check(near(rules.converted(15.999,99.5,3),15.999),"levels below 16 stay unchanged");
        check(near(rules.converted(24,99.5,1),9.36),"normalized level then judge multiplier exactly once");
        check(near(rules.converted(12,99.5,3),12),"level 12 / C / 99.5 equals 12.0000");
        check(near(rules.converted(12,99.8,3),12.12),"level 12 / C / 99.8 equals 12.1200");
        check(near(rules.converted(12,100,3),12.12),"99.8 and above are capped at 101 percent");
        check(near(rules.converted(12,99.65,3),12.06),"middle accuracy interpolates continuously");
        check(near(rules.converted(12,99.5,1),9.36),"judge A multiplier applied once");
        check(near(rules.converted(12,99.8,5),14.544),"judge E multiplier applied once");
        check(near(rules.converted(12,0,3),0),"zero accuracy yields zero converted rating");
        check(Double.isNaN(rules.converted(12,99.8,0)),"unknown judge does not silently become C");
        double prior=-1;for(int i=0;i<=100000;i++){double next=rules.converted(12,i/1000.0,3);if(next+1e-10<prior)throw new AssertionError("nonmonotonic");prior=next;}check(true,"accuracy curve is monotonic throughout 0 to 100");
        boolean invalid=false;try{RatingRules.accuracyFactor(100.1);}catch(IllegalArgumentException e){invalid=true;}check(invalid,"accuracy above 100 rejected");
        List<PosterData.Entry> entries=new ArrayList<>();for(int i=1;i<=25;i++)entries.add(entry(i,i,"O",i));for(int i=1;i<=15;i++)entries.add(entry(100+i,100+i,"N",i));
        entries.add(entry(999,25,"O",99));PosterData.Board board=PosterData.group(entries);
        check(board.old.size()==20&&board.fresh.size()==10,"O20 and N10 have independent capacities");
        check(board.old.get(0).cid==999,"same song keeps the best result across charts");
        check(board.fresh.get(0).converted==15,"new songs sorted independently of daily songs");
        Set<Integer> songs=new HashSet<>();for(PosterData.Entry e:board.old)check(songs.add(e.sid),"old-table song unique "+e.sid);
        List<PosterData.Entry> shared=Arrays.asList(entry(1,1,"O",20),entry(2,1,"N",15));PosterData.Board moved=PosterData.group(shared);
        check(moved.old.isEmpty()&&moved.fresh.size()==1,"promoted song appears in N only");
    }
}
