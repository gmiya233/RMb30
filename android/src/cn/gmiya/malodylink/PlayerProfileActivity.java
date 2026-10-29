package cn.gmiya.malodylink;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.view.*;
import android.widget.*;

/** Local photo picker: no storage permission, networking, or cloud integration. */
public final class PlayerProfileActivity extends Activity {
    private static final int BG=AppUi.BG,INK=AppUi.INK,MUTED=AppUi.MUTED,ACCENT=AppUi.ACCENT;
    private ImageView preview;private EditText nickname;private TextView status;private Bitmap image;private boolean avatarChanged,busy;private String profile;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(8),0,dp(8));return t;}
    private void button(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(16);b.setTextColor(INK);b.setMinHeight(dp(48));b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(AppUi.SOFT));parent.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->{if(!busy)action.run();});}
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);profile=ScoreStore.profile(this);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(dp(24),dp(16),dp(24),dp(24));scroll.addView(form);setContentView(scroll);scroll.setOnApplyWindowInsetsListener((v,in)->{scroll.setPadding(in.getSystemWindowInsetLeft(),in.getSystemWindowInsetTop(),in.getSystemWindowInsetRight(),in.getSystemWindowInsetBottom());return in;});
        button(form,"返回",this::finish);TextView title=text("头像与昵称",26,INK);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);form.addView(title);form.addView(text("仅保存在此手机，不随成绩上传。头像会居中裁成正方形。",14,MUTED));
        preview=new ImageView(this);preview.setScaleType(ImageView.ScaleType.CENTER_CROP);preview.setContentDescription("本地头像预览");GradientDrawable bg=new GradientDrawable();bg.setColor(AppUi.SOFT);bg.setCornerRadius(dp(16));preview.setBackground(bg);preview.setClipToOutline(true);LinearLayout.LayoutParams portrait=new LinearLayout.LayoutParams(dp(160),dp(160));portrait.gravity=Gravity.CENTER_HORIZONTAL;portrait.setMargins(0,dp(16),0,dp(16));form.addView(preview,portrait);image=LocalPlayerProfile.avatarBitmap(this);showAvatar();
        button(form,"从本地选择头像",()->startActivityForResult(LocalPlayerProfile.avatarPicker(this),61));button(form,"恢复默认头像",()->{image=null;avatarChanged=true;showAvatar();});
        form.addView(text("显示昵称（留空使用成绩中的玩家名）",14,INK));nickname=new EditText(this);nickname.setTextColor(INK);nickname.setTextSize(16);nickname.setSingleLine(true);nickname.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});nickname.setText(LocalPlayerProfile.nickname(this,""));form.addView(nickname,new LinearLayout.LayoutParams(-1,dp(56)));
        status=text("设置不会改变原始成绩或 QQ 存档编号。称号请在 B30 页选择。",13,MUTED);form.addView(status);button(form,"保存到本机",()->{if(!profile.equals(ScoreStore.profile(this))){status.setText("QQ存档已切换，请返回后重新设置");return;}try{LocalPlayerProfile.save(this,nickname.getText().toString(),image,avatarChanged);setResult(RESULT_OK);finish();}catch(Exception e){status.setText(e.getMessage());}});
    }
    private void showAvatar(){if(image==null){preview.setImageResource(android.R.drawable.ic_menu_myplaces);preview.setColorFilter(ACCENT);}else{preview.clearColorFilter();preview.setImageBitmap(image);}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=61||result!=RESULT_OK||data==null||data.getData()==null)return;busy=true;status.setText("正在处理本地图片…");new Thread(()->{try{Bitmap next=LocalPlayerProfile.prepareAvatar(getContentResolver().openInputStream(data.getData()));runOnUiThread(()->{busy=false;if(isDestroyed()){next.recycle();return;}image=next;avatarChanged=true;showAvatar();status.setText("头像已准备好，点击保存后生效。");});}catch(Exception e){runOnUiThread(()->{busy=false;status.setText("无法使用这张图片："+e.getMessage());});}}).start();}
}
