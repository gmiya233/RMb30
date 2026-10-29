package cn.gmiya.malodylink;

import android.app.Activity;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Vertical board sections with native horizontal carousels and explicit top-50 pages. */
public final class RankingHub extends LinearLayout {
    private final Activity host;private final Engine engine;private SongCatalogue catalogue;private final BiConsumer<Boolean,Integer> remember;private final Consumer<String> searchMemory;private String query,boardId="total",notice="";
    private final ExecutorService io=Executors.newSingleThreadExecutor();private CommunityData.Boards boards;private RankingPanel singlePanel;private ScrollView scroll;private TextView status;private boolean closed,loading,showHiddenCreators;private int page,request,homeY;
    public RankingHub(Activity host,Engine engine,boolean single,int cid,String query,BiConsumer<Boolean,Integer> remember,Consumer<String> searchMemory){super(host);this.host=host;this.engine=engine;this.query=query;this.remember=remember;this.searchMemory=searchMemory;showHiddenCreators=host.getSharedPreferences("community",0).getBoolean("show_hidden_creators",false);catalogue=new SongCatalogue(engine.charts());setOrientation(VERTICAL);setBackground(new CharacterBackdrop(host,2));if(single)openSingle(cid);else{home();load(true);}}
    private int dp(int n){return AppUi.dp(host,n);}
    private void clear(){if(scroll!=null&&page==0)homeY=scroll.getScrollY();scroll=null;if(singlePanel!=null){singlePanel.close();singlePanel=null;}removeAllViews();setPadding(dp(16),dp(16),dp(16),0);}
    private void title(String text){addView(AppUi.text(host,text,26,AppUi.INK,true));AppUi.space(this,8);}
    private void buttons(String left,Runnable first,String right,Runnable second){LinearLayout row=new LinearLayout(host);row.addView(AppUi.button(host,left,first),new LayoutParams(0,-2,1));LayoutParams rp=new LayoutParams(0,-2,1);rp.leftMargin=dp(8);row.addView(AppUi.button(host,right,second),rp);addView(row);}
    private JSONArray rows(String id){try{return boards==null?new JSONArray():boards.rows(id.equals("creator_hot")&&showHiddenCreators&&boards.doc.getJSONObject("boards").has("creator_hot_all")?"creator_hot_all":id);}catch(Exception invalid){return new JSONArray();}}
    private void creatorToggle(LinearLayout parent){CheckBox toggle=new CheckBox(host);toggle.setText("显示默认隐藏的谱师");toggle.setTextColor(AppUi.INK);toggle.setTextSize(14);toggle.setMinHeight(dp(48));toggle.setChecked(showHiddenCreators);parent.addView(toggle);parent.addView(AppUi.text(host,"imddesigner 始终隐藏",12,AppUi.MUTED,false));AppUi.space(parent,8);toggle.setOnCheckedChangeListener((v,checked)->{showHiddenCreators=checked;host.getSharedPreferences("community",0).edit().putBoolean("show_hidden_creators",checked).apply();render();});}
    private void labelStatus(){status=AppUi.text(host,loading?"正在读取榜单…":notice.isEmpty()?(boards!=null&&boards.cached?"显示缓存 · ":"")+"热度按下载次数 · 好评按平均星级":notice,12,AppUi.MUTED,false);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);status.setPadding(0,dp(8),0,dp(12));addView(status);}
    private void home(){
        clear();page=0;remember.accept(false,0);title("排行榜");buttons("累计活跃榜",()->ExtraBoards.show(host,engine,0),"近30天活跃榜",()->ExtraBoards.show(host,engine,1));addView(AppUi.button(host,"6K rating 排行",()->ExtraBoards.show(host,engine,2)));buttons("单曲榜 · 选歌",()->openSingle(0),"刷新榜单",()->load(true));labelStatus();
        scroll=new ScrollView(host);scroll.setFillViewport(true);LinearLayout body=AppUi.column(host,0);scroll.addView(body);addView(scroll,new LayoutParams(-1,0,1));
        for(int i=0;i<CommunityData.IDS.length;i++){
            final String id=CommunityData.IDS[i];LinearLayout heading=new LinearLayout(host);heading.setGravity(Gravity.CENTER_VERTICAL);heading.addView(AppUi.text(host,CommunityData.TITLES[i],20,AppUi.INK,true),new LayoutParams(0,-2,1));heading.addView(AppUi.text(host,"左右滑动",12,AppUi.MUTED,false));body.addView(heading);AppUi.space(body,10);
            if(id.equals("creator_hot"))creatorToggle(body);
            HorizontalScrollView horizontal=new HorizontalScrollView(host);horizontal.setContentDescription(CommunityData.TITLES[i]+"，左右滑动查看");horizontal.setFillViewport(false);horizontal.setHorizontalScrollBarEnabled(true);LinearLayout strip=new LinearLayout(host);horizontal.addView(strip);JSONArray data=rows(id);
            if(data.length()==0){LinearLayout empty=AppUi.column(host,16);empty.setGravity(Gravity.CENTER);empty.setBackground(surface(AppUi.CARD));empty.addView(AppUi.text(host,CommunityData.good(id)?"暂无评价\n去歌曲页为喜欢的作品打分":"暂无已发布数据",14,AppUi.MUTED,false));LayoutParams size=new LayoutParams(dp(184),dp(184));size.rightMargin=dp(10);strip.addView(empty,size);}
            for(int n=0;n<Math.min(10,data.length());n++){JSONObject row=data.optJSONObject(n);LayoutParams size=new LayoutParams(dp(156),dp(184));size.rightMargin=dp(10);strip.addView(preview(id,row),size);}
            Button more=AppUi.button(host,"查看更多\n前50名 →",()->more(id));more.setGravity(Gravity.CENTER);strip.addView(more,new LayoutParams(dp(144),dp(184)));body.addView(horizontal);AppUi.space(body,24);
        }
        final ScrollView current=scroll;current.post(()->current.scrollTo(0,homeY));
    }
    private GradientDrawable surface(int color){GradientDrawable bg=AppUi.background(host,color);bg.setStroke(dp(1),AppUi.BORDER);return bg;}
    private TextView text(String value,int size,int color,boolean bold,int lines){TextView text=AppUi.text(host,value,size,color,bold);text.setMaxLines(lines);text.setEllipsize(android.text.TextUtils.TruncateAt.END);return text;}
    private String value(String id,JSONObject row){if(id.equals("total"))return String.format(Locale.ROOT,"rating %.4f",row.optDouble("rating"));if(CommunityData.good(id))return String.format(Locale.ROOT,"%.2f星 · %d评",row.optDouble("average"),row.optInt("rating_count"));return String.format(Locale.ROOT,"下载 %,d 次",row.optLong("downloads"));}
    private View art(String id,JSONObject row,int size){
        SongCatalogue.Song song=catalogue.forChart(row.optInt("cid"));if(id.startsWith("song_")&&song!=null){ImageView image=new ImageView(host);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(AppUi.background(host,AppUi.SOFT));image.setClipToOutline(true);image.setContentDescription(song.title+"曲绘");image.setLayoutParams(new LayoutParams(dp(size),dp(size)));SongArtwork.load(host,image,song);return image;}
        String name=row.optString("name","?");TextView initial=AppUi.text(host,name.isEmpty()?"?":name.substring(0,name.offsetByCodePoints(0,1)).toUpperCase(Locale.ROOT),22,AppUi.ACCENT,true);initial.setGravity(Gravity.CENTER);initial.setBackground(AppUi.background(host,AppUi.SELECTED));initial.setLayoutParams(new LayoutParams(dp(size),dp(size)));return initial;
    }
    private void action(View card,String id,JSONObject row){if(!id.startsWith("song_"))return;SongCatalogue.Song song=catalogue.forChart(row.optInt("cid"));if(song==null)return;card.setOnClickListener(v->SongPanel.details(host,song,(s,cid)->openSingle(cid)));card.setFocusable(true);}
    private View preview(String id,JSONObject row){
        LinearLayout card=AppUi.column(host,10);card.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x24076d78),surface(id.equals("total")&&engine.profile.equals(row.optString("qq"))?AppUi.SELECTED:AppUi.CARD),null));card.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView rank=text("#"+row.optInt("rank"),13,row.optInt("rank")<=3?AppUi.GOLD:AppUi.MUTED,true,1);card.addView(rank);AppUi.space(card,4);card.addView(art(id,row,40));AppUi.space(card,4);TextView name=text(row.optString("name"),14,AppUi.INK,true,2);name.setGravity(Gravity.CENTER);name.setMinLines(2);card.addView(name,new LayoutParams(-1,-2));TextView value=text(value(id,row),id.equals("total")?16:14,AppUi.ACCENT,true,1);value.setGravity(Gravity.CENTER);card.addView(value,new LayoutParams(-1,-2));TextView subtitle=text(id.equals("total")?"O20 + N10":row.optString("subtitle"),12,AppUi.MUTED,false,1);subtitle.setGravity(Gravity.CENTER);card.addView(subtitle,new LayoutParams(-1,-2));action(card,id,row);return card;
    }
    private String explanation(String id){if(id.equals("total"))return "O20 + N10 · 统一默认规则 · 前50名";if(id.equals("song_hot"))return "同一歌曲各谱面下载次数相加 · 前50名";if(id.equals("creator_hot"))return "按谱师署名汇总作品下载次数 · 前50名";return "按平均星级排序，同分优先评价数 · 前50名";}
    private void more(String id){
        clear();page=1;boardId=id;title(CommunityData.title(id));buttons("返回榜单首页",this::home,"刷新榜单",()->load(true));addView(AppUi.text(host,explanation(id),13,AppUi.MUTED,false));if(id.equals("creator_hot"))creatorToggle(this);labelStatus();JSONArray data=rows(id);ListView list=new ListView(host);list.setDivider(new ColorDrawable(AppUi.BG));list.setDividerHeight(dp(10));list.setSelector(new ColorDrawable(android.graphics.Color.TRANSPARENT));list.setPadding(0,0,0,dp(12));list.setClipToPadding(false);addView(list,new LayoutParams(-1,0,1));
        if(data.length()==0){list.setAdapter(new ArrayAdapter<>(host,android.R.layout.simple_list_item_1,new String[]{CommunityData.good(id)?"暂无评价，可从歌曲页发表评论并打分":"暂无已发布数据"}));return;}
        list.setAdapter(new BaseAdapter(){public int getCount(){return Math.min(50,data.length());}public Object getItem(int p){return data.optJSONObject(p);}public long getItemId(int p){return p;}public View getView(int p,View recycled,ViewGroup parent){JSONObject row=data.optJSONObject(p);LinearLayout card=AppUi.column(host,14);card.setBackground(surface(id.equals("total")&&engine.profile.equals(row.optString("qq"))?AppUi.SELECTED:AppUi.CARD));LinearLayout top=new LinearLayout(host);top.setGravity(Gravity.CENTER_VERTICAL);top.addView(text("#"+row.optInt("rank"),20,row.optInt("rank")<=3?AppUi.GOLD:AppUi.MUTED,true,1),new LayoutParams(dp(52),-2));top.addView(art(id,row,48));LinearLayout info=AppUi.column(host,0);LayoutParams ip=new LayoutParams(0,-2,1);ip.leftMargin=dp(12);top.addView(info,ip);info.addView(text(row.optString("name"),17,AppUi.INK,true,2));info.addView(text(value(id,row),18,AppUi.ACCENT,true,1));info.addView(text(id.equals("total")?String.format(Locale.ROOT,"O20 %.4f · N10 %.4f",row.optDouble("o"),row.optDouble("n")):row.optString("subtitle"),12,AppUi.MUTED,false,2));card.addView(top);action(card,id,row);return card;}});
    }
    private void openSingle(int cid){clear();page=2;setPadding(0,0,0,0);remember.accept(true,cid);singlePanel=new RankingPanel(host,engine,true,cid,query,(single,chosen)->remember.accept(true,chosen),value->{query=value;searchMemory.accept(value);},this::home);addView(singlePanel,new LayoutParams(-1,-1));}
    private void deliver(Runnable action){host.runOnUiThread(()->{if(!closed&&!host.isDestroyed())action.run();});}
    private void render(){if(page==0)home();else if(page==1)more(boardId);}
    private CommunityData.Boards fallback()throws Exception {RankingRepository.Snapshot source=RankingRepository.refresh(host,engine,false,progress->{});JSONObject data=new JSONObject();for(String id:CommunityData.IDS)data.put(id,new JSONArray());JSONArray totals=data.getJSONArray("total");for(RankingData.Player player:RankingData.total(source.players)){if(totals.length()==50)break;totals.put(RankingData.encode(player).put("rank",player.rank));}CommunityData.Boards result=new CommunityData.Boards();result.doc=new JSONObject().put("schema",1).put("server",engine.server).put("revision",RankingIndex.revision(engine.server,engine.charts())).put("updated",source.updated).put("boards",data);result.cached=true;return result;}
    private void load(boolean force){if(closed||loading)return;loading=true;notice="";int current=++request;if(status!=null)status.setText("正在读取榜单…");
        io.execute(()->{try{CommunityData.Boards cache=CommunityRepository.cachedBoards(host,engine);if(cache!=null)deliver(()->{if(current!=request)return;boards=cache;render();});CommunityData.Boards next=CommunityRepository.boards(host,engine,force);deliver(()->{if(current!=request)return;boards=next;catalogue=new SongCatalogue(engine.charts());loading=false;notice="";render();});}
            catch(Exception failure){CommunityData.Boards legacy=null;if(boards==null)try{legacy=fallback();}catch(Exception ignored){}final CommunityData.Boards saved=legacy;deliver(()->{if(current!=request)return;if(saved!=null)boards=saved;loading=false;notice="社区榜单刷新未完成，保留已有总榜与缓存。";render();});}});
    }
    public boolean back(){if(page==0)return false;if(page==2&&singlePanel!=null&&singlePanel.backToChooser())return true;home();return true;}
    public void close(){closed=true;request++;if(singlePanel!=null)singlePanel.close();io.shutdown();}
}
