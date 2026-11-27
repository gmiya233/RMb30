package cn.gmiya.malodylink;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Inline file browser. Package contents are copied/validated only after the user selects them. */
public final class ChartImportActivity extends Activity {
    private static final int PICK=73,CLEAN_DIRECTORY=75;
    private static final class Row {QqDownloads.Source source;String name,error="";ChartPackage.Item item;boolean selected;}
    private static final class Session {
        final ExecutorService io=Executors.newSingleThreadExecutor();final List<Row> rows=new ArrayList<>();final List<File> queue=new ArrayList<>();
        final String id=UUID.randomUUID().toString();volatile Runnable changed;volatile boolean busy;volatile String message="先在 QQ 批量下载，再在这里多选谱面包。";
        volatile boolean cleaning;volatile int cleanupDone,cleanupTotal;
        String loadedPath;File output;boolean compatible=false,refreshAfterQQ,gameLaunched,awaitingGame;int next;
        void notifyUi(){Runnable c=changed;if(c!=null)c.run();}
    }
    private int gridSide=3,rowPixels;
    private Session session;private TextView status,summary,empty;private Button pick,refreshButton,paths,select,prepare,open,clean,nextPackage;private ProgressBar progress;private GridView grid;private FileAdapter adapter;private Runnable callback;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);gridSide=Math.max(2,Math.min(4,getSharedPreferences("qq_downloads",0).getInt("grid_side",3)));rowPixels=dp(174);session=(Session)getLastNonConfigurationInstance();boolean fresh=session==null;if(fresh)session=new Session();
        getWindow().setStatusBarColor(AppUi.BG);getWindow().setNavigationBarColor(AppUi.BG);
        LinearLayout body=AppUi.column(this,16);body.setBackgroundColor(AppUi.BG);setContentView(body);body.setOnApplyWindowInsetsListener((v,in)->{body.setPadding(dp(16)+in.getSystemWindowInsetLeft(),dp(12)+in.getSystemWindowInsetTop(),dp(16)+in.getSystemWindowInsetRight(),dp(12)+in.getSystemWindowInsetBottom());return in;});
        body.addView(AppUi.button(this,"返回",this::finish));AppUi.space(body,8);body.addView(AppUi.text(this,"群文件 · 批量导入",25,AppUi.INK,true));body.addView(AppUi.text(this,"QQ 批量下载 → 勾选文件卡片 → 导入 Malody",14,AppUi.MUTED,false));
        LinearLayout actions=new LinearLayout(this);body.addView(actions);pick=AppUi.button(this,"选择其他谱面包",this::choose);actions.addView(pick,new LinearLayout.LayoutParams(0,-2,1));Button qq=AppUi.button(this,"打开 QQ",()->{Intent i=getPackageManager().getLaunchIntentForPackage("com.tencent.mobileqq");if(i==null)Toast.makeText(this,"未找到手机 QQ",Toast.LENGTH_SHORT).show();else{session.refreshAfterQQ=true;startActivity(i);}});actions.addView(qq,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout folders=new LinearLayout(this);body.addView(folders);refreshButton=AppUi.button(this,"刷新目录",this::browseDirectory);folders.addView(refreshButton,new LinearLayout.LayoutParams(0,-2,1));paths=AppUi.button(this,"下载路径设置",this::settings);folders.addView(paths,new LinearLayout.LayoutParams(0,-2,1));
        status=AppUi.text(this,"",14,AppUi.ACCENT,false);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(status);progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);body.addView(progress,new LinearLayout.LayoutParams(-1,dp(8)));
        LinearLayout selection=new LinearLayout(this);selection.setGravity(Gravity.CENTER_VERTICAL);body.addView(selection);summary=AppUi.text(this,"",15,AppUi.INK,true);selection.addView(summary,new LinearLayout.LayoutParams(0,-2,1));select=AppUi.button(this,"全选",this::selectAll);selection.addView(select);clean=AppUi.button(this,"清理MCZ",this::cleanup);selection.addView(clean);
        LinearLayout density=new LinearLayout(this);density.setGravity(Gravity.CENTER_VERTICAL);body.addView(density);density.addView(AppUi.text(this,"每屏",13,AppUi.MUTED,false));Spinner densityPicker=new Spinner(this);ArrayAdapter<String> densities=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,new String[]{"2×2","3×3","4×4"}){
            private View row(int position,boolean dropdown){LinearLayout line=new LinearLayout(ChartImportActivity.this);line.setGravity(Gravity.CENTER_VERTICAL);line.setPadding(dp(10),0,dp(10),0);line.setMinimumHeight(dp(48));ImageView icon=new ImageView(ChartImportActivity.this);icon.setImageDrawable(new FileGridIcon(position+2));icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);line.addView(icon,new LinearLayout.LayoutParams(dp(24),dp(24)));TextView label=AppUi.text(ChartImportActivity.this,getItem(position),14,AppUi.INK,false);label.setPadding(dp(10),0,0,0);line.addView(label);return line;}
            @Override public View getView(int p,View v,ViewGroup parent){return row(p,false);}@Override public View getDropDownView(int p,View v,ViewGroup parent){return row(p,true);}
        };densityPicker.setAdapter(densities);densityPicker.setSelection(gridSide-2);densityPicker.setContentDescription("文件卡片每屏网格");density.addView(densityPicker,new LinearLayout.LayoutParams(dp(148),dp(48)));densityPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){changeGrid(position+2);}});
        FrameLayout files=new FrameLayout(this);body.addView(files,new LinearLayout.LayoutParams(-1,0,1));grid=new GridView(this){@Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);if(w!=oldw||h!=oldh)post(()->layoutGrid());}};grid.setNumColumns(gridSide);grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setHorizontalSpacing(dp(8));grid.setVerticalSpacing(dp(8));grid.setPadding(0,dp(8),0,dp(8));grid.setClipToPadding(false);files.addView(grid,new FrameLayout.LayoutParams(-1,-1));
        empty=AppUi.text(this,"",16,AppUi.MUTED,false);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(24),dp(24),dp(24));files.addView(empty,new FrameLayout.LayoutParams(-1,-1));grid.setEmptyView(empty);adapter=new FileAdapter();grid.setAdapter(adapter);
        prepare=AppUi.button(this,"检查并准备导入",this::prepare);prepare.setTextColor(AppUi.ON_SUCCESS);prepare.setBackgroundTintList(android.content.res.ColorStateList.valueOf(AppUi.SUCCESS));body.addView(prepare);
        Switch mode=new Switch(this);mode.setText("逐包导入（兼容选项，默认一次导入全部所选）");mode.setTextSize(14);mode.setTextColor(AppUi.INK);mode.setMinHeight(dp(48));mode.setChecked(session.compatible);body.addView(mode);mode.setOnCheckedChangeListener((v,on)->{if(session.busy){mode.setChecked(session.compatible);return;}session.compatible=on;session.awaitingGame=false;session.output=null;session.message=on?"逐包交给 Malody，确认后继续下一包。":"一次性导入：生成 MCB 曲包，每首歌独立，只发送一次。";refresh();});
        open=AppUi.button(this,"交给 Malody",this::openGame);body.addView(open);nextPackage=AppUi.button(this,"已在游戏中确认 · 下一包",this::nextPackage);body.addView(nextPackage);body.addView(AppUi.text(this,"只显示 MCZ，默认一次导入全部所选；文件合计和解压后各不超过1GB。清理仅删除下载文件。",12,AppUi.MUTED,false));
        changeGrid(gridSide);callback=()->runOnUiThread(()->{if(!isDestroyed())refresh();});session.changed=callback;refresh();if(fresh&&!uris(getIntent()).isEmpty())receive(getIntent());
    }
    private int dp(int n){return AppUi.dp(this,n);}
    private void changeGrid(int side){gridSide=side;getSharedPreferences("qq_downloads",0).edit().putInt("grid_side",side).apply();grid.setNumColumns(side);layoutGrid();}
    private static final class FileGridIcon extends android.graphics.drawable.Drawable {
        private final int side;private final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        FileGridIcon(int side){this.side=side;paint.setColor(AppUi.ACCENT);paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setStrokeWidth(1.5f);}
        @Override public void draw(android.graphics.Canvas canvas){android.graphics.Rect b=getBounds();canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);float cell=20f/side;for(int y=0;y<side;y++)for(int x=0;x<side;x++)canvas.drawRoundRect(2+x*cell,2+y*cell,2+(x+1)*cell-1.8f,2+(y+1)*cell-1.8f,.8f,.8f,paint);canvas.restore();}
        @Override public void setAlpha(int a){paint.setAlpha(a);}@Override public void setColorFilter(android.graphics.ColorFilter f){paint.setColorFilter(f);}@Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
    private void layoutGrid(){if(grid==null||adapter==null||grid.getHeight()<=0)return;int gap=dp(gridSide==4?6:8);grid.setHorizontalSpacing(gap);grid.setVerticalSpacing(gap);int available=grid.getHeight()-grid.getPaddingTop()-grid.getPaddingBottom();int next=Math.max(dp(48),(available-gap*(gridSide-1))/gridSide);if(next!=rowPixels){rowPixels=next;adapter.notifyDataSetChanged();}else adapter.notifyDataSetChanged();}

    @Override protected void onResume(){super.onResume();if(session.awaitingGame&&session.output!=null&&!session.busy){session.message=ChartImportProvider.wasOpened(this,outputUri())?"Malody 已打开当前文件。请在游戏曲库确认歌曲；未出现可重试，确认后再进入下一包。":"Malody 尚未读取当前文件。请等待游戏进入主界面后，返回这里点重试发送当前包。";refresh();}String key=QqDownloads.selectionKey(this);boolean changed=!key.equals(session.loadedPath);if(!session.busy&&(changed||session.refreshAfterQQ)){session.refreshAfterQQ=false;if(!QqDownloads.selected(this).isEmpty())browseDirectory();else{session.loadedPath=key;refresh();}}}
    @Override public Object onRetainNonConfigurationInstance(){return session;}
    @Override protected void onDestroy(){if(session.changed==callback)session.changed=null;if(!isChangingConfigurations())session.io.shutdown();super.onDestroy();}
    private void settings(){if(!session.busy)startActivity(new Intent(this,QqDownloadSettingsActivity.class).putExtra("return_to_import",true));}
    private void choose(){if(session.busy)return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);String saved=QqDownloads.selected(this);Uri initial=QqDownloads.manual(this)&&!saved.isEmpty()?Uri.parse(saved):DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:"+(saved.startsWith("/storage/emulated/0/")?saved.substring(20):"Download/QQ"));i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,initial);startActivityForResult(i,PICK);}
    private String key(QqDownloads.Source s){return s.remote!=null?"adb:"+s.remote.path:"uri:"+s.uri;}
    private void browseDirectory(){
        if(session.busy)return;final String requestedPath=QqDownloads.selectionKey(this);if(!requestedPath.equals(session.loadedPath)){synchronized(session.rows){session.rows.clear();}session.output=null;}session.loadedPath=requestedPath;if(QqDownloads.selected(this).isEmpty()){session.message="请先设置 QQ 下载路径，保存后文件会自动显示在这里。";refresh();return;}
        session.busy=true;session.message="正在读取下载目录…";refresh();session.io.execute(()->{try{
            QqDownloads.Listing listing=QqDownloads.listing(getApplicationContext());List<QqDownloads.Source> sources=listing.files;Map<String,Row> previous=new HashMap<>();synchronized(session.rows){for(Row r:session.rows)previous.put(key(r.source),r);}
            List<Row> fresh=new ArrayList<>();for(QqDownloads.Source s:sources){Row r=previous.get(key(s));if(r==null||r.source.size!=s.size||r.source.modified!=s.modified){r=new Row();r.name=s.name;}r.source=s;fresh.add(r);}
            runOnUiThread(()->{if(requestedPath.equals(QqDownloads.selectionKey(getApplicationContext()))){synchronized(session.rows){session.rows.clear();session.rows.addAll(fresh);}session.loadedPath=requestedPath;session.message="找到 "+sources.size()+" 个谱面包 · 最新下载在前 · 点击卡片勾选"+(listing.warning.isEmpty()?"":"\n"+listing.warning);}else{session.loadedPath=null;session.message="路径已变更，请刷新目录。";}session.busy=false;session.notifyUi();});
        }catch(Exception e){session.message=message(e);session.busy=false;session.notifyUi();}});
    }
    private List<Uri> uris(Intent i){LinkedHashSet<Uri> values=new LinkedHashSet<>();if(i.getData()!=null)values.add(i.getData());try{Object stream=i.getParcelableExtra(Intent.EXTRA_STREAM);if(stream instanceof Uri)values.add((Uri)stream);}catch(Exception ignored){}try{ArrayList<Uri> streams=i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);if(streams!=null)values.addAll(streams);}catch(Exception ignored){}if(i.getClipData()!=null)for(int n=0;n<i.getClipData().getItemCount();n++){Uri u=i.getClipData().getItemAt(n).getUri();if(u!=null)values.add(u);}return new ArrayList<>(values);}
    private void receive(Intent intent){
        List<Uri> sources=uris(intent);if(sources.isEmpty()||session.busy)return;if(sources.size()>ChartPackage.MAX_PACKAGES){session.message="一次最多接收5000个文件，请分批选择。";refresh();return;}
        session.loadedPath=QqDownloads.selectionKey(this);session.busy=true;session.output=null;refresh();session.io.execute(()->{try{
            List<Row> received=new ArrayList<>();for(Uri uri:sources){Row row=new Row();row.source=new QqDownloads.Source();row.source.uri=uri;row.name="QQ谱面包.zip";row.selected=true;
                try{if(!"content".equals(uri.getScheme())||uri.getAuthority()!=null&&uri.getAuthority().startsWith(getPackageName()))throw new IOException("请使用系统选择器或 QQ 授权的原始文件");try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){if(c!=null&&c.moveToFirst()){if(c.getString(0)!=null)row.name=c.getString(0);row.source.size=c.getLong(1);}}if(!QqDownloadPolicy.isMcz(row.name))throw new IOException("这里只接收 .MCZ 文件");}catch(Exception e){row.error=message(e);row.selected=false;}row.source.name=row.name;received.add(row);
            }runOnUiThread(()->{synchronized(session.rows){session.rows.clear();session.rows.addAll(received);}session.message="已接收文件，请确认勾选后检查并导入。";session.busy=false;session.notifyUi();});
        }catch(Exception e){session.message=message(e);session.busy=false;session.notifyUi();}});
    }
    private int selectedCount(){int count=0;synchronized(session.rows){for(Row r:session.rows)if(r.selected)count++;}return count;}
    private void toggle(Row row,boolean checked){if(session.busy)return;if(checked&&selectedCount()>=ChartPackage.MAX_PACKAGES){Toast.makeText(this,"每批最多5000包，请分批导入",Toast.LENGTH_SHORT).show();adapter.notifyDataSetChanged();return;}row.selected=checked;session.output=null;refresh();}
    private void selectAll(){if(session.busy)return;boolean clear=selectedCount()>0;int count=0;synchronized(session.rows){for(Row r:session.rows){r.selected=!clear&&count<ChartPackage.MAX_PACKAGES&&QqDownloadPolicy.isMcz(r.name);if(r.selected)count++;}}session.output=null;session.message=clear?"已取消选择。":"已选择前 "+count+" 包，可点击卡片调整。";refresh();}
    private String message(Exception e){return e.getMessage()==null?"处理未完成，请刷新目录后重试":e.getMessage();}
    private void refresh(){
        int count=0,unknown=0,charts=0;synchronized(session.rows){for(Row r:session.rows)if(r.selected){count++;if(r.item==null)unknown++;else charts+=r.item.charts;}}
        summary.setText("已选 "+count+" / "+session.rows.size()+" 包"+(unknown>0?" · "+unknown+" 包待检查":charts>0?" · "+charts+" 张谱面":""));status.setText(session.message);progress.setIndeterminate(!session.cleaning);if(session.cleaning){progress.setMax(session.cleanupTotal);progress.setProgress(session.cleanupDone);progress.setContentDescription("清理进度，已处理 "+session.cleanupDone+" / "+session.cleanupTotal+" 个文件");}progress.setVisibility(session.busy?View.VISIBLE:View.GONE);pick.setEnabled(!session.busy);paths.setEnabled(!session.busy);refreshButton.setEnabled(!session.busy);select.setEnabled(!session.busy&&!session.rows.isEmpty());clean.setEnabled(!session.busy);select.setText(count>0?"取消选择":"全选");prepare.setEnabled(!session.busy&&count>0);prepare.setText((session.compatible?"检查并准备逐包导入":"准备一次性导入")+(count>0?"（"+count+"包）":""));
        open.setVisibility(session.output==null?View.GONE:View.VISIBLE);open.setEnabled(!session.busy);open.setText(session.awaitingGame?"重试发送当前包":session.compatible?"发送到 Malody · 第 "+(session.next+1)+" / "+session.queue.size()+" 包":"一次性导入全部所选");nextPackage.setVisibility(session.awaitingGame&&session.output!=null?View.VISIBLE:View.GONE);nextPackage.setEnabled(!session.busy);nextPackage.setText(session.next+1<session.queue.size()?"已在游戏中确认 · 下一包":"已在游戏中确认 · 完成");empty.setText(session.busy?"正在读取文件…":QqDownloads.selected(this).isEmpty()?"设置下载路径后\n这里会显示可多选的谱面文件卡片":"尚未列出可读取的 MCZ\n请查看上方扫描结果，或检查下载路径与授权");adapter.notifyDataSetChanged();
    }
    private final class FileAdapter extends BaseAdapter {
        public int getCount(){return session.rows.size();}public Object getItem(int position){return session.rows.get(position);}public long getItemId(int position){return position;}
        public View getView(int position,View recycled,ViewGroup parent){
            Row r=session.rows.get(position);boolean compact=gridSide==4,small=rowPixels<dp(110),tiny=rowPixels<dp(76);LinearLayout card=AppUi.column(ChartImportActivity.this,tiny?4:compact?6:gridSide==3?10:16);card.setLayoutParams(new AbsListView.LayoutParams(-1,rowPixels));GradientDrawable bg=AppUi.background(ChartImportActivity.this,r.selected?AppUi.SELECTED:AppUi.CARD);bg.setStroke(dp(r.selected?2:1),r.selected?AppUi.ACCENT:AppUi.BORDER);card.setBackground(bg);
            LinearLayout top=new LinearLayout(ChartImportActivity.this);top.setGravity(Gravity.CENTER_VERTICAL);if(!tiny)card.addView(top);TextView type=AppUi.text(ChartImportActivity.this,r.name.toLowerCase(Locale.ROOT).endsWith(".mcz")?"MCZ":"ZIP",12,AppUi.ACCENT,true);top.addView(type,new LinearLayout.LayoutParams(0,-2,1));TextView state=AppUi.text(ChartImportActivity.this,r.selected?"✓ 已选":"未选",12,r.selected?AppUi.ACCENT:AppUi.MUTED,r.selected);top.addView(state);
            CheckBox check=new CheckBox(ChartImportActivity.this);check.setButtonDrawable((android.graphics.drawable.Drawable)null);check.setText((tiny?(r.selected?"✓ ":"□ "):"")+r.name);check.setTextSize(compact?12:gridSide==3?14:16);check.setTextColor(AppUi.INK);check.setTypeface(null,Typeface.BOLD);check.setMinLines(tiny?1:2);check.setMaxLines(tiny?1:small?2:compact?2:3);check.setEllipsize(TextUtils.TruncateAt.END);check.setMinimumHeight(0);check.setMinHeight(0);check.setPadding(0,dp(4),0,dp(4));check.setGravity(Gravity.CENTER_VERTICAL);check.setChecked(r.selected);check.setEnabled(!session.busy&&QqDownloadPolicy.isMcz(r.name));check.setContentDescription(r.name+(r.selected?"，已选择":"，未选择"));card.addView(check,new LinearLayout.LayoutParams(-1,0,1));check.setOnCheckedChangeListener((v,on)->toggle(r,on));
            java.text.SimpleDateFormat date=new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA);String info=String.format(Locale.ROOT,"%.1f MB",r.source.size/1048576d)+(!compact&&!small&&r.source.modified>0?" · "+date.format(new Date(r.source.modified*1000)):"");TextView size=AppUi.text(ChartImportActivity.this,info,12,AppUi.MUTED,false);size.setSingleLine();size.setEllipsize(TextUtils.TruncateAt.END);card.addView(size);
            if(!tiny&&(!compact||!r.error.isEmpty())){TextView detail=AppUi.text(ChartImportActivity.this,!r.error.isEmpty()?r.error:r.item==null?"导入前检查谱面包":r.item.charts+" 张谱面 · "+TextUtils.join(" / ",r.item.titles),12,r.error.isEmpty()?AppUi.MUTED:0xffa73434,false);detail.setMaxLines(gridSide==2?2:1);detail.setEllipsize(TextUtils.TruncateAt.END);card.addView(detail);}
            card.setOnClickListener(v->{if(!session.busy)check.performClick();});return card;
        }
    }

    private void inspect(Row row,File dir)throws Exception {
        if(row.item!=null){row.error="";return;}QqDownloads.Source source=row.source;if(!QqDownloadPolicy.isMcz(row.name))throw new IOException("这里只接收 .MCZ 文件");if(source.size>ChartPackage.MAX_PACKAGE_BYTES)throw new IOException("单包超过256MB，请分批整理");File copy=new File(dir,UUID.randomUUID()+".zip");boolean valid=false;
        try{if(source.remote!=null)QqDownloads.copy(getApplicationContext(),source,copy);else{
                if(source.uri==null||!"content".equals(source.uri.getScheme())||source.uri.getAuthority()!=null&&source.uri.getAuthority().startsWith(getPackageName()))throw new IOException("文件授权无效，请重新选择");
                try(InputStream in=getContentResolver().openInputStream(source.uri);OutputStream out=new BufferedOutputStream(new FileOutputStream(copy))){if(in==null)throw new IOException("文件无法读取，请确认已下载完成");byte[] b=new byte[65536];long size=0;int n;while((n=in.read(b))!=-1){size+=n;if(size>ChartPackage.MAX_PACKAGE_BYTES||copy.getUsableSpace()<4L*1024*1024)throw new IOException("文件过大或存储空间不足");out.write(b,0,n);}}
            }row.item=ChartPackage.inspect(copy,row.name);row.source.size=copy.length();row.error="";valid=true;
        }finally{if(!valid)copy.delete();}
    }
    private void cleanup(){
        if(session.busy)return;List<Row> selected=new ArrayList<>(),all=new ArrayList<>();String scope=QqDownloads.selectionKey(this);synchronized(session.rows){for(Row r:session.rows)if(QqDownloadPolicy.isMcz(r.name)&&scope.equals(r.source.directoryKey)){all.add(r);if(r.selected)selected.add(r);}}
        List<String> options=new ArrayList<>();if(!selected.isEmpty())options.add("清理已选 "+selected.size()+" 个 MCZ");if(!all.isEmpty())options.add("清理当前列表全部 "+all.size()+" 个 MCZ");options.add("选择其他目录清理…");
        new AlertDialog.Builder(this).setTitle("清理 MCZ 文件").setItems(options.toArray(new String[0]),(d,n)->{if(n==options.size()-1)chooseCleanupDirectory();else confirmCleanup(!selected.isEmpty()&&n==0?selected:all);}).setNegativeButton("取消",null).show();
    }
    private void chooseCleanupDirectory(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download"));startActivityForResult(intent,CLEAN_DIRECTORY);
    }
    private void loadCleanupDirectory(Intent data){
        Uri tree=data.getData();int flags=data.getFlags();session.busy=true;session.message="正在检查所选清理目录中的 MCZ…";refresh();session.io.execute(()->{try{
            List<Row> targets=new ArrayList<>();for(QqDownloads.Source source:QqDownloads.cleanupSources(getApplicationContext(),tree,flags)){Row row=new Row();row.source=source;row.name=source.name;targets.add(row);}
            runOnUiThread(()->{session.busy=false;session.message="所选清理目录找到 "+targets.size()+" 个 MCZ，下载路径设置未改变。";session.notifyUi();if(!isDestroyed()&&!targets.isEmpty())confirmCleanup(targets,tree);});
        }catch(Exception e){session.busy=false;session.message=message(e);session.notifyUi();}});
    }

    private void confirmCleanup(List<Row> targets){confirmCleanup(targets,null);}
    private void confirmCleanup(List<Row> targets,Uri tree){
        long bytes=0;StringBuilder names=new StringBuilder();for(int n=0;n<targets.size();n++){bytes+=Math.max(0,targets.get(n).source.size);if(n<4)names.append("\n• ").append(targets.get(n).name);}if(targets.size()>4)names.append("\n等 ").append(targets.size()).append(" 个文件");
        String content=(tree==null?QqDownloads.description(this):"所选清理目录\n"+DocumentsContract.getTreeDocumentId(tree))+"\n\n将永久删除 "+targets.size()+" 个 MCZ，共 "+String.format(Locale.ROOT,"%.1f MB",bytes/1048576d)+"。无法撤销。只删除下载文件，不删除 Malody 内已导入的谱面或成绩。\n"+names;
        new AlertDialog.Builder(this).setTitle("确认清理下载文件？").setMessage(content).setNegativeButton("取消",null).setPositiveButton("删除 "+targets.size()+" 个 MCZ",(d,w)->runCleanup(targets,tree)).show();
    }
    private void runCleanup(List<Row> targets,Uri tree){
        if(session.busy)return;session.cleanupDone=0;session.cleanupTotal=targets.size();session.cleaning=true;session.busy=true;session.output=null;session.message="正在清理 MCZ · 已处理 0 / "+session.cleanupTotal;refresh();session.io.execute(()->{
            List<Row> removed=new ArrayList<>();int failed=0;String firstError="";for(Row row:targets){
                session.message="正在清理 MCZ · 已处理 "+session.cleanupDone+" / "+session.cleanupTotal+"\n"+row.name;session.notifyUi();
                try{if(tree==null)QqDownloads.remove(getApplicationContext(),row.source);else QqDownloads.removeCleanup(getApplicationContext(),row.source,tree);removed.add(row);}catch(Exception e){row.error="清理失败："+message(e);if(firstError.isEmpty())firstError=message(e);failed++;}
                session.cleanupDone++;session.message="正在清理 MCZ · 已处理 "+session.cleanupDone+" / "+session.cleanupTotal+" · "+(session.cleanupDone*100/session.cleanupTotal)+"%\n已删除 "+removed.size()+" 个 · 失败 "+failed+" 个";session.notifyUi();
            }
            final int errors=failed;final String reason=firstError;runOnUiThread(()->{synchronized(session.rows){session.rows.removeAll(removed);if(tree!=null)session.loadedPath=null;}session.cleaning=false;session.busy=false;session.message="已清理 "+removed.size()+" 个 MCZ"+(errors>0?"，"+errors+" 个未清理："+reason:"。Malody 中的谱面和成绩未改动。");session.notifyUi();});
        });
    }
    private void prepare(){
        if(session.busy)return;List<Row> rows=new ArrayList<>();synchronized(session.rows){for(Row r:session.rows)if(r.selected)rows.add(r);}if(rows.isEmpty()||rows.size()>ChartPackage.MAX_PACKAGES)return;
        final boolean compatible=session.compatible;session.awaitingGame=false;session.busy=true;session.output=null;session.queue.clear();session.next=0;refresh();session.io.execute(()->{try{
            File dir=new File(new File(getCacheDir(),"chart-imports"),session.id);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法创建暂存目录");Set<String> hashes=new HashSet<>();List<ChartPackage.Item> items=new ArrayList<>();int failed=0;long raw=0,expanded=0;
            for(int n=0;n<rows.size();n++){Row r=rows.get(n);session.message="检查 "+(n+1)+" / "+rows.size()+" · "+r.name;session.notifyUi();try{inspect(r,dir);if(!hashes.add(r.item.digest))throw new IOException("与其他已选谱面包重复，已取消选择");raw+=r.item.file.length();expanded+=r.item.expanded;if(raw>ChartPackage.MAX_BATCH_BYTES||expanded>ChartPackage.MAX_BATCH_BYTES)throw new IOException("本批文件合计超过1GB，请减少选择");items.add(r.item);}catch(Exception e){r.error=message(e);r.selected=false;failed++;}}
            if(failed>0){session.message=failed+" 个包未通过检查，卡片已标记原因。请确认其余选择后再次准备导入。";return;}
            int count=compatible?items.size():1;for(int n=0;n<count;n++){File target=new File(dir,"batch-"+UUID.randomUUID()+(compatible?".mcz":".mcb"));session.queue.add(ChartPackage.bundle(compatible?Collections.singletonList(items.get(n)):items,target,t->{session.message=t;session.notifyUi();}));}
            session.output=session.queue.get(0);session.message=compatible?"谱面已准备好。逐包交给 Malody，确认后继续下一包。":"已将 "+items.size()+" 个谱面包整理为 MCB 曲包，每首歌独立。点击一次性导入全部所选。";
        }catch(Exception e){session.message=message(e);}finally{session.busy=false;session.notifyUi();}});
    }
    private Uri outputUri(){return Uri.parse("content://"+getPackageName()+".chartimports/"+session.id+"/"+session.output.getName());}
    private void sendPreparedPackage(){
        Uri uri=outputUri();ChartImportProvider.resetReceipt(this,uri);
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/octet-stream").setPackage("me.mugzone.emiria").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.setClipData(ClipData.newRawUri("Malody 谱面导入",uri));
        try{getApplicationContext().startActivity(intent);session.awaitingGame=true;session.message="已发送当前谱面包，等待 Malody 读取。请在游戏内确认歌曲。";}
        catch(ActivityNotFoundException e){session.message="未找到可以接收谱面的 Malody V。";}
        catch(Exception e){session.message="发送未完成："+message(e);}
        finally{session.busy=false;session.notifyUi();}
    }
    private void openGame(){
        if(session.output==null||session.busy)return;
        if(session.gameLaunched){sendPreparedPackage();return;}
        new AlertDialog.Builder(this).setTitle("一次性导入前准备")
            .setMessage("请先让 Malody 进入主界面，再发送谱面。游戏尚未启动时，先打开游戏，返回这里即可一次发送全部所选。")
            .setPositiveButton("游戏已在主界面，开始导入",(d,w)->{session.gameLaunched=true;sendPreparedPackage();})
            .setNeutralButton("先打开游戏",(d,w)->launchGameForImport()).setNegativeButton("取消",null).show();
    }
    private void launchGameForImport(){
        Intent launch=getPackageManager().getLaunchIntentForPackage("me.mugzone.emiria");
        if(launch==null){session.message="未找到 Malody V。";refresh();return;}
        try{startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));session.gameLaunched=true;session.message="请等待 Malody 进入主界面，再返回这里点一次性导入。所选谱面已保留。";refresh();}
        catch(Exception e){session.message="启动游戏失败："+message(e);refresh();}
    }
    private void nextPackage(){
        if(session.busy||!session.awaitingGame)return;
        new AlertDialog.Builder(this).setTitle("已在 Malody 曲库看到歌曲？").setMessage("只有确认歌曲已出现，才跳过当前包。没有出现请返回并重试发送。").setNegativeButton("返回检查",null).setPositiveButton("已确认",(d,w)->{
            session.next++;session.awaitingGame=false;session.output=session.next<session.queue.size()?session.queue.get(session.next):null;
            session.message=session.output==null?"本批谱面已由你确认完成。":"当前包已确认，请发送下一包。";refresh();
        }).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==PICK&&result==RESULT_OK&&data!=null)receive(data);if(request==CLEAN_DIRECTORY&&result==RESULT_OK&&data!=null)loadCleanupDirectory(data);}
}
