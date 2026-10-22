package cn.gmiya.malodylink;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Song-level reviews, one current rating per QQ archive. */
public final class SongCommunityActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();private final Handler handler=new Handler(Looper.getMainLooper());
    private Engine engine;private SongCatalogue.Song song;private String topic,pending="";private JSONObject snapshot;private TextView summary,status;private RatingBar average;private ListView list;private Button write,refresh;private int generation;private boolean closed,posting;private AlertDialog editor;
    public static void open(Activity host,int cid){host.startActivity(new Intent(host,SongCommunityActivity.class).putExtra("cid",cid));}
    private int dp(int value){return AppUi.dp(this,value);}
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(AppUi.BG);getWindow().setNavigationBarColor(AppUi.BG);
        try{engine=new Engine(this);song=new SongCatalogue(engine.charts()).forChart(getIntent().getIntExtra("cid",0));if(song==null)throw new IllegalArgumentException("歌曲不在当前谱库");topic=CommunityData.topic(song);build();snapshot=CommunityRepository.cachedSong(this,engine,topic);if(snapshot!=null)render();load(0);}catch(Exception failure){LinearLayout body=AppUi.column(this,20);body.addView(AppUi.text(this,"无法打开评论："+failure.getMessage(),16,AppUi.INK,false));body.addView(AppUi.button(this,"返回",this::finish));setContentView(body);}
    }
    private void build(){
        LinearLayout body=AppUi.column(this,16);body.setBackground(new CharacterBackdrop(this,3));setContentView(body);body.setOnApplyWindowInsetsListener((v,in)->{body.setPadding(dp(16)+in.getSystemWindowInsetLeft(),dp(12)+in.getSystemWindowInsetTop(),dp(16)+in.getSystemWindowInsetRight(),dp(12)+in.getSystemWindowInsetBottom());return in;});
        LinearLayout actions=new LinearLayout(this);actions.addView(AppUi.button(this,"返回歌曲",this::finish),new LinearLayout.LayoutParams(0,-2,1));refresh=AppUi.button(this,"刷新评论",()->load(0));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,-2,1);rp.leftMargin=dp(8);actions.addView(refresh,rp);body.addView(actions);AppUi.space(body,12);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);ImageView art=new ImageView(this);art.setScaleType(ImageView.ScaleType.CENTER_CROP);art.setContentDescription(song.title+"曲绘");header.addView(art,new LinearLayout.LayoutParams(dp(64),dp(64)));SongArtwork.load(this,art,song);LinearLayout info=AppUi.column(this,0);LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(0,-2,1);ip.leftMargin=dp(12);header.addView(info,ip);TextView title=AppUi.text(this,song.title,20,AppUi.INK,true);title.setMaxLines(2);info.addView(title);info.addView(AppUi.text(this,"评论与评分",14,AppUi.MUTED,false));body.addView(header);AppUi.space(body,10);
        average=new RatingBar(this,null,android.R.attr.ratingBarStyleSmall);average.setNumStars(5);average.setStepSize(.1f);average.setIsIndicator(true);body.addView(average,new LinearLayout.LayoutParams(-2,-2));summary=AppUi.text(this,"正在读取评分…",14,AppUi.MUTED,false);body.addView(summary);
        write=AppUi.button(this,engine.profile.isEmpty()?"先设置 QQ 再评价":"写评论 / 打分",this::edit);write.setTextColor(AppUi.ON_SUCCESS);write.setBackgroundTintList(android.content.res.ColorStateList.valueOf(AppUi.ACCENT));body.addView(write,new LinearLayout.LayoutParams(-1,-2));
        status=AppUi.text(this,"每个 QQ 对同一首歌保留最新评价，可修改；评论最多500字。",12,AppUi.MUTED,false);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);status.setPadding(0,dp(8),0,dp(8));body.addView(status);list=new ListView(this);list.setDivider(new android.graphics.drawable.ColorDrawable(AppUi.BG));list.setDividerHeight(dp(10));list.setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void deliver(Runnable action){runOnUiThread(()->{if(!closed&&!isDestroyed())action.run();});}
    private void load(int attempt){if(closed||engine==null)return;final int request=++generation;refresh.setEnabled(false);status.setText(pending.isEmpty()?"正在读取歌曲评价…":"评价已提交，等待服务器汇总…");
        io.execute(()->{try{JSONObject next=CommunityRepository.song(this,engine,topic);deliver(()->{if(request!=generation)return;snapshot=next;refresh.setEnabled(true);render();if(!pending.isEmpty()){
                    JSONObject mine=mine();if(mine!=null&&pending.equals(mine.optString("nonce"))){pending="";status.setText("评价已更新。每个 QQ 对同一首歌保留最新评价，可修改。");}
                    else if(attempt<4)handler.postDelayed(()->load(attempt+1),1500);else status.setText("评价已保存，汇总稍后更新，可点击刷新查看。");
                }});}catch(Exception failure){deliver(()->{if(request!=generation)return;refresh.setEnabled(true);status.setText((snapshot!=null?"显示上次缓存。":"")+"读取未完成："+message(failure));});}});
    }
    private static String message(Exception failure){return failure.getMessage()==null?"请稍后重试":failure.getMessage();}
    private JSONObject mine(){if(snapshot==null)return null;JSONArray rows=snapshot.optJSONArray("reviews");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject review=rows.optJSONObject(i);if(review!=null&&engine.profile.equals(review.optString("qq")))return review;}return null;}
    private void render(){
        if(snapshot==null)return;int total=snapshot.optInt("rating_count");double score=snapshot.optDouble("average",0);average.setRating((float)score);average.setContentDescription(total>0?String.format(Locale.ROOT,"平均 %.2f 星，%d 人评分",score,total):"暂无评分");summary.setText((total>0?String.format(Locale.ROOT,"%.2f / 5 · %d 人评分",score,total):"暂无评分")+" · 下载 "+snapshot.optLong("downloads")+" 次");
        JSONArray reviews=snapshot.optJSONArray("reviews");int count=reviews==null?0:Math.min(50,reviews.length());if(count==0){list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,new String[]{"还没有评价，来分享你的游玩感受吧。"}));status.setText("每个 QQ 对同一首歌保留最新评价，可修改；评论最多500字。");return;}
        status.setText("最近 "+count+" 条评价 · 每个 QQ 对同一首歌保留最新评价");
        list.setAdapter(new BaseAdapter(){public int getCount(){return count;}public Object getItem(int p){return reviews.optJSONObject(p);}public long getItemId(int p){return p;}public View getView(int position,View recycled,ViewGroup parent){JSONObject review=reviews.optJSONObject(position);LinearLayout card=AppUi.column(SongCommunityActivity.this,14);card.setBackground(AppUi.background(SongCommunityActivity.this,AppUi.CARD));card.addView(AppUi.text(SongCommunityActivity.this,review.optString("name")+(engine.profile.equals(review.optString("qq"))?" · 我":""),16,AppUi.INK,true));RatingBar stars=new RatingBar(SongCommunityActivity.this,null,android.R.attr.ratingBarStyleSmall);stars.setNumStars(5);stars.setStepSize(1);stars.setIsIndicator(true);stars.setRating(review.optInt("stars"));stars.setContentDescription(review.optInt("stars")+"星");card.addView(stars,new LinearLayout.LayoutParams(-2,-2));AppUi.space(card,6);TextView text=AppUi.text(SongCommunityActivity.this,review.optString("text").isEmpty()?"仅评分":review.optString("text"),15,AppUi.INK,false);text.setTextIsSelectable(true);card.addView(text);AppUi.space(card,6);SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd HH:mm",Locale.CHINA);format.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));card.addView(AppUi.text(SongCommunityActivity.this,format.format(new Date(review.optLong("updated"))),12,AppUi.MUTED,false));return card;}});
    }
    private String name(){String name="QQ "+engine.profile;long latest=-1;for(int i=0;i<engine.scores.length();i++){JSONObject score=engine.scores.optJSONObject(i);if(score!=null&&!score.optString("player").trim().isEmpty()&&score.optLong("played_at")>latest){name=score.optString("player").trim();latest=score.optLong("played_at");}}return LocalPlayerProfile.nickname(this,name);}
    private void edit(){
        if(posting)return;if(engine.profile.isEmpty()){new AlertDialog.Builder(this).setMessage("请先在设置中填写 QQ 存档编号，再发表评论和评分。").setPositiveButton("前往设置",(d,w)->startActivity(new Intent(this,MainActivity.class).putExtra("tab",4))).setNegativeButton("取消",null).show();return;}
        ScrollView scroll=new ScrollView(this);LinearLayout form=AppUi.column(this,20);scroll.addView(form);form.addView(AppUi.text(this,"以 "+name()+" · QQ "+engine.profile+" 评价",14,AppUi.MUTED,false));AppUi.space(form,12);form.addView(AppUi.text(this,"评分（1～5星）",16,AppUi.INK,true));RatingBar stars=new RatingBar(this);stars.setNumStars(5);stars.setStepSize(1);stars.setContentDescription("选择1到5星评分");form.addView(stars,new LinearLayout.LayoutParams(-2,-2));EditText text=new EditText(this);text.setHint("分享你的游玩感受（可选，最多500字）");text.setTextSize(15);text.setTextColor(AppUi.INK);text.setMinLines(3);text.setMaxLines(6);text.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(500)});form.addView(text,new LinearLayout.LayoutParams(-1,-2));JSONObject own=mine();if(!pending.isEmpty()||own==null)own=CommunityRepository.ownReview(this,engine,topic);if(own!=null){stars.setRating(own.optInt("stars"));text.setText(own.optString("text"));}
        editor=new AlertDialog.Builder(this).setTitle("评论与评分").setView(scroll).setNegativeButton("取消",null).setPositiveButton("提交评价",null).create();editor.setOnShowListener(ignored->editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(stars.getRating()<1){text.setError("请先选择1～5星评分");return;}
            try{JSONObject review=CommunityData.review(engine.server,topic,engine.profile,name(),Math.round(stars.getRating()),text.getText().toString());posting=true;write.setEnabled(false);Button submit=editor.getButton(AlertDialog.BUTTON_POSITIVE);submit.setEnabled(false);submit.setText("正在提交…");AlertDialog dialog=editor;
                io.execute(()->{try{CommunityRepository.post(this,engine,review);deliver(()->{posting=false;write.setEnabled(true);dialog.dismiss();pending=review.optString("nonce");status.setText("评价已提交，正在更新…");handler.postDelayed(()->load(0),1000);});}catch(Exception failure){deliver(()->{posting=false;write.setEnabled(true);submit.setEnabled(true);submit.setText("提交评价");text.setError("提交未完成："+message(failure));});}});
            }catch(Exception failure){text.setError(message(failure));}
        }));editor.show();editor.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    @Override protected void onDestroy(){closed=true;generation++;handler.removeCallbacksAndMessages(null);if(editor!=null&&editor.isShowing())editor.dismiss();io.shutdown();super.onDestroy();}
}
