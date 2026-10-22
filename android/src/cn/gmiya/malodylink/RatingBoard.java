package cn.gmiya.malodylink;

import java.util.*;

/** Pure ranking model shared unchanged by Android and the server. */
public class RatingBoard {
    public static class Entry {
        public int cid,sid,judge,keys;public double level,accuracy,converted;public long score;
        public String difficultyVersion;public boolean levelOverride;
        public double displayLevel(){return levelOverride?RatingRules.normalizedLevel(level):ChartDetails.level(level,difficultyVersion);}
        public String title,version,player,section;public boolean manual,demo;
        public String identity(){return sid>0?"sid:"+sid:"cid:"+cid;}
    }
    public static class Board {
        public List<Entry> old=new ArrayList<>(),fresh=new ArrayList<>();
        public String player="PLAYER",title="";public boolean demo;public int allCount;
        public int count(){return old.size()+fresh.size();}
        public double oldRating(){return average(old,20);}
        public double newRating(){return average(fresh,10);}
        public double rating(){return (oldRating()*20+newRating()*10)/30;}
        private static double average(List<Entry> entries,int slots){double sum=0;for(Entry e:entries)if(Double.isFinite(e.converted))sum+=e.converted;return sum/slots;}
    }
    public static final Comparator<Entry> ORDER=(a,b)->{
        boolean af=Double.isFinite(a.converted),bf=Double.isFinite(b.converted);
        if(af!=bf)return af?-1:1;
        int r=af?Double.compare(b.converted,a.converted):0;
        if(r==0)r=Double.compare(b.accuracy,a.accuracy);
        if(r==0)r=Long.compare(b.score,a.score);return r==0?Integer.compare(a.cid,b.cid):r;
    };
    public static Board group(List<Entry> entries){
        Map<String,Entry> best=new HashMap<>();
        // Deduplicate a song across charts; N status wins if any current chart is promoted.
        Set<String> freshSongs=new HashSet<>();for(Entry e:entries)if("N".equals(e.section))freshSongs.add(e.identity());
        for(Entry e:entries){String key=e.identity();Entry prev=best.get(key);if(prev==null||ORDER.compare(e,prev)<0)best.put(key,e);}
        Board b=new Board();b.allCount=best.size();for(Entry e:best.values()){e.section=freshSongs.contains(e.identity())?"N":"O";if("N".equals(e.section))b.fresh.add(e);else b.old.add(e);}
        b.old.sort(ORDER);b.fresh.sort(ORDER);if(b.old.size()>20)b.old=new ArrayList<>(b.old.subList(0,20));if(b.fresh.size()>10)b.fresh=new ArrayList<>(b.fresh.subList(0,10));return b;
    }
}
