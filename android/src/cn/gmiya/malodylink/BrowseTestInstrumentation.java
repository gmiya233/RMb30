package cn.gmiya.malodylink;

import android.app.*;
import android.graphics.BitmapFactory;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reads real catalogue/cloud storage only; never creates or uploads player records. */
public final class BrowseTestInstrumentation extends Instrumentation {
    private void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    @Override public void onCreate(Bundle b){super.onCreate(b);start();}
    @Override public void onStart(){Bundle result=new Bundle();int code=-1;try{
        Engine engine=new Engine(getTargetContext());File raw=ScoreStore.file(getTargetContext(),engine.profile,"scores.json");byte[] before=ScoreStore.exists(raw)?ScoreStore.read(raw):null;
        SongCatalogue catalogue=new SongCatalogue(engine.charts());check(catalogue.songs.size()>700,"current catalogue songs");int metadata=0;for(SongCatalogue.Song song:catalogue.songs)if(!song.bpm.isEmpty()&&!song.charts.get(0).optString("creator").isEmpty())metadata++;check(metadata>500,"BPM and mapper available in upgraded local catalogue");
        RankingRepository.Snapshot live=RankingRepository.refresh(getTargetContext(),engine,true,p->{});check(live.failed==0,"all cloud archives readable");check(!live.players.isEmpty(),"real uploaded player present");List<RankingData.Player> total=RankingData.total(live.players);check(Double.isFinite(total.get(0).rating),"real total rating computed");
        int chartCount=0;for(RankingData.Player player:live.players)for(int cid:player.best.keySet()){RankingRepository.Snapshot single=RankingRepository.refresh(getTargetContext(),engine,true,cid,p->{});check(single.cid==cid&&!RankingData.chart(single.players,cid).isEmpty(),"dedicated single chart index");for(RankingData.Player one:single.players)check(one.best.size()==1&&one.best.containsKey(cid),"single chart request excludes other charts");chartCount++;}check(chartCount>0,"real chart scores available");
        RankingRepository.Snapshot cached=RankingRepository.cached(getTargetContext(),engine);check(cached.cached&&cached.players.size()==live.players.size(),"offline snapshot persisted");
        OpenListScoreRemote remote=RankConnection.open(getTargetContext());boolean artwork=false;for(SongCatalogue.Song song:catalogue.songs){if(song.cover.isEmpty())continue;String path=song.cover.replace('\\','/').replaceAll("^/+","");try{byte[] image=remote.songArt(CloudRecords.sha256(path.getBytes(StandardCharsets.UTF_8)));check(BitmapFactory.decodeByteArray(image,0,image.length)!=null,"published thumbnail decodes on Android");artwork=true;break;}catch(IOException missing){}}check(artwork,"at least one cloud artwork available");
        check(Arrays.equals(before,ScoreStore.exists(raw)?ScoreStore.read(raw):null),"browsing preserves local score archive");
        result.putString("stream","PASS: compact RT index, dedicated single chart indices, real chart ratings, cached snapshot, BPM/mapper enrichment, Android artwork decode, unchanged local scores. Players="+live.players.size()+", charts="+chartCount+".\n");
    }catch(Throwable e){code=0;StringWriter trace=new StringWriter();e.printStackTrace(new PrintWriter(trace));result.putString("stream","FAIL: "+trace+"\n");}finish(code,result);}
}
