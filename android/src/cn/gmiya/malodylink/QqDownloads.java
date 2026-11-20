package cn.gmiya.malodylink;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;

/** User-selected SAF trees or a strictly scoped on-device reader for QQ download folders. */
public final class QqDownloads {

    public static final class Source {public String name;public Uri uri;public QqDownloadPolicy.Entry remote;public long size,modified;public String directoryKey="";}
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("qq_downloads",0);}
    public static boolean manual(Context c){return prefs(c).getString("mode","auto").equals("manual");}
    public static String selected(Context c){return prefs(c).getString(manual(c)?"tree":"root","");}
    public static String selectionKey(Context c){return (manual(c)?"manual:":"auto:")+selected(c)+":"+prefs(c).getString("auto_tree","")+":"+prefs(c).getLong("changed",0);}
    public static String description(Context c){String s=selected(c);if(s.isEmpty())return "尚未设置下载目录";if(manual(c)){try{return "手动选择 · "+DocumentsContract.getTreeDocumentId(Uri.parse(s)).replaceFirst("^primary:","内部存储/");}catch(Exception invalid){return "手动选择的下载目录";}}return "自动检测 · "+s;}
    public static void auto(Context c,String root)throws IOException {QqDownloadPolicy.root(root);prefs(c).edit().putString("mode","auto").putString("root",root).putString("auto_tree","").putLong("changed",System.currentTimeMillis()).apply();}
    public static void tree(Context c,Uri tree,int flags)throws Exception {
        if(tree==null||!"content".equals(tree.getScheme())||!DocumentsContract.isTreeUri(tree))throw new IOException("请选择文件夹");
        c.getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|(flags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
        List<Source> check=new ArrayList<>();walk(c,tree,DocumentsContract.getTreeDocumentId(tree),check,0,new int[]{0});
        prefs(c).edit().putString("mode","manual").putString("tree",tree.toString()).putLong("changed",System.currentTimeMillis()).apply();
    }
    private static InternalAdb connect(Context c)throws Exception {
        InternalAdb adb=InternalAdb.get(c);try{LocalScoreSync.connect(c,adb,0);return adb;}catch(Exception e){throw new IOException("本机读取未连接，请开启无线调试并检查首次配对，或改用手动选择目录",e);}
    }
    private static List<Source> treeSources(Context c,Uri tree)throws Exception {
        List<Source> rows=new ArrayList<>();try{walk(c,tree,DocumentsContract.getTreeDocumentId(tree),rows,0,new int[]{0});}catch(SecurityException expired){throw new IOException("目录授权已失效，请重新选择文件夹");}rows.sort((a,b)->Long.compare(b.modified,a.modified));for(Source source:rows)source.directoryKey=selectionKey(c);return rows;
    }
    public static void auto(Context c,QqDownloadPolicy.Directory d)throws Exception {
        if(!d.available())throw new IOException(d.error);
        if(d.tree.isEmpty())QqDownloadPolicy.root(d.path);
        prefs(c).edit().putString("mode","auto").putString("root",d.path).putString("auto_tree",d.tree).putLong("changed",System.currentTimeMillis()).apply();
    }
    public static List<QqDownloadPolicy.Directory> detect(Context c)throws Exception {
        final Map<String,QqDownloadPolicy.Directory> grants=new HashMap<>();
        // Previously granted folders remain usable even when wireless debugging is off.
        for(UriPermission grant:c.getContentResolver().getPersistedUriPermissions()){
            if(!grant.isReadPermission()||!DocumentsContract.isTreeUri(grant.getUri()))continue;
            Uri tree=grant.getUri();String id;try{id=DocumentsContract.getTreeDocumentId(tree);}catch(Exception invalid){continue;}
            if(!"com.android.externalstorage.documents".equals(tree.getAuthority())||!id.startsWith("primary:"))continue;
            String root="/storage/emulated/0/"+id.substring(8);
            boolean known=false;for(String candidate:QqDownloadPolicy.ROOTS)if(candidate.equals(root))known=true;
            if(!known)continue;
            try{List<Source> rows=treeSources(c,tree);QqDownloadPolicy.Directory d=new QqDownloadPolicy.Directory();d.path=root;d.tree=tree.toString();d.count=rows.size();d.newest=rows.isEmpty()?0:rows.get(0).modified;grants.put(root,d);}catch(Exception unavailable){}
        }
        final InternalAdb[] connection={null};final Exception[] failed={null};final boolean[] held={false},attempted={false};
        try{return QqDownloadPolicy.scan(root->{
            if(grants.containsKey(root))return grants.get(root);
            int wireless=-1;try{wireless=android.provider.Settings.Global.getInt(c.getContentResolver(),"adb_wifi_enabled",-1);}catch(Exception unknown){}
            if(wireless==0)throw new IOException("未检查：请开启无线调试，或手动授权此目录");
            if(!attempted[0]){attempted[0]=true;try{if(!ScoreSyncGate.enter("qq-downloads"))throw new IOException("同步进行中，请稍后检查此目录");held[0]=true;connection[0]=connect(c);}catch(Exception e){failed[0]=e;}}
            if(failed[0]!=null)throw failed[0];InternalAdb adb=connection[0];
            try{if(!adb.qqDirectoryExists(root))return null;QqDownloadPolicy.Listing listing=adb.qqPackages(root);List<QqDownloadPolicy.Entry> entries=listing.entries;QqDownloadPolicy.Directory d=new QqDownloadPolicy.Directory();d.path=root;d.warning=listing.warning;d.count=entries.size();d.newest=entries.isEmpty()?0:entries.get(0).modified;return d;}catch(InternalAdb.TransportException e){failed[0]=e;throw e;}
        });}finally{if(held[0])ScoreSyncGate.leave("qq-downloads");}
    }
    public static final class Listing {
        public final List<Source> files;public final String warning;
        Listing(List<Source> files,String warning){this.files=files;this.warning=warning;}
    }
    public static Listing listing(Context c)throws Exception {
        if(manual(c)){String value=selected(c);if(value.isEmpty())throw new IOException("请先选择下载目录");return new Listing(treeSources(c,Uri.parse(value)),"");}
        String root=selected(c);if(root.isEmpty()){List<QqDownloadPolicy.Directory> choices=detect(c);QqDownloadPolicy.Directory ready=null;for(QqDownloadPolicy.Directory d:choices)if(d.available()){ready=d;break;}if(ready==null)throw new IOException("没有可读取的 QQ 下载目录，请手动授权或检查本机读取");auto(c,ready);root=ready.path;}
        String granted=prefs(c).getString("auto_tree","");if(!granted.isEmpty())return new Listing(treeSources(c,Uri.parse(granted)),"");
        if(!ScoreSyncGate.enter("qq-downloads"))throw new IOException("正在同步成绩，请稍后读取下载目录");
        try{InternalAdb adb=connect(c);if(!adb.qqDirectoryExists(root))throw new IOException("保存的目录已不存在，请重新自动检测或手动选择");List<Source> rows=new ArrayList<>();
            QqDownloadPolicy.Listing listing=adb.qqPackages(root);for(QqDownloadPolicy.Entry e:listing.entries){Source s=new Source();s.remote=e;s.name=e.path.substring(e.path.lastIndexOf('/')+1);s.size=e.size;s.modified=e.modified;s.directoryKey=selectionKey(c);rows.add(s);}return new Listing(rows,listing.warning);
        }finally{ScoreSyncGate.leave("qq-downloads");}
    }
    public static void copy(Context c,Source source,File target)throws Exception {
        if(source.remote==null)throw new IOException("文件来源无效");if(!ScoreSyncGate.enter("qq-downloads"))throw new IOException("正在同步成绩，请稍后复制谱面包");
        try{connect(c).copyQQPackage(source.remote,target);}finally{ScoreSyncGate.leave("qq-downloads");}
    }
    public static void remove(Context c,Source source)throws Exception {
        if(source.directoryKey.isEmpty()||!source.directoryKey.equals(selectionKey(c))||!QqDownloadPolicy.isMcz(source.name))throw new IOException("仅可清理当前下载目录列出的 MCZ，请刷新目录");
        if(source.remote!=null){
            if(manual(c)||!source.remote.path.startsWith(QqDownloadPolicy.root(selected(c))+"/"))throw new IOException("文件不属于当前下载目录");
            if(!ScoreSyncGate.enter("qq-downloads"))throw new IOException("其他同步正在进行，请稍后清理");
            try{connect(c).removeQQPackage(source.remote);}finally{ScoreSyncGate.leave("qq-downloads");}return;
        }
        String saved=manual(c)?prefs(c).getString("tree",""):prefs(c).getString("auto_tree","");
        if(saved.isEmpty())throw new IOException("目录授权无效，请重新选择下载路径");
        removeFromTree(c,source,Uri.parse(saved));
    }
    /** One-off cleanup scope, separate from the saved QQ download directory. */
    public static List<Source> cleanupSources(Context c,Uri tree,int flags)throws Exception {
        if(tree==null||!"content".equals(tree.getScheme())||!DocumentsContract.isTreeUri(tree))throw new IOException("请选择文件夹");
        if((flags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)==0)throw new IOException("目录只有读取授权，请选择允许文件管理的目录");
        List<Source> files=treeSources(c,tree);for(Source source:files)source.directoryKey="cleanup:"+tree;return files;
    }
    public static void removeCleanup(Context c,Source source,Uri tree)throws Exception {
        if(tree==null||!source.directoryKey.equals("cleanup:"+tree)||!QqDownloadPolicy.isMcz(source.name))throw new IOException("文件不属于本次选择的清理目录");
        removeFromTree(c,source,tree);
    }
    private static void removeFromTree(Context c,Source source,Uri tree)throws Exception {
        if(source.uri==null)throw new IOException("文件授权无效，请重新选择目录");
        if(!Objects.equals(tree.getAuthority(),source.uri.getAuthority())||!DocumentsContract.getTreeDocumentId(tree).equals(DocumentsContract.getTreeDocumentId(source.uri)))throw new IOException("文件超出当前下载目录");
        if(c.checkUriPermission(source.uri,android.os.Process.myPid(),android.os.Process.myUid(),Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IOException("目录只有读取授权，请到下载路径设置重新手动选择，允许文件管理后再清理");
        try(Cursor cursor=c.getContentResolver().query(source.uri,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
            if(cursor==null||!cursor.moveToFirst())throw new IOException("文件已不存在，请刷新目录");
            if(DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(3)))throw new IOException("清理不会删除文件夹");
            QqDownloadPolicy.verifyRemoval(source.name,source.size,source.modified,cursor.getString(0),cursor.getLong(1),cursor.getLong(2)/1000);
        }
        if(!DocumentsContract.deleteDocument(c.getContentResolver(),source.uri))throw new IOException("文件提供方未完成删除");
    }
    private static void walk(Context c,Uri tree,String id,List<Source> rows,int depth,int[] visited)throws Exception {
        if(depth>6)throw new IOException("目录层级超过6层，请选择更具体的下载目录");
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,id);
        try(Cursor cursor=c.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED},null,null,null)){
            if(cursor==null)throw new IOException("无法读取所选目录，请重新授权");
            List<String> folders=new ArrayList<>();while(cursor.moveToNext()){
                if(++visited[0]>10000)throw new IOException("所选目录文件过多，请选择 QQ 下载子目录");String child=cursor.getString(0),name=cursor.getString(1),mime=cursor.getString(2);
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){folders.add(child);continue;}
                if(name==null||!QqDownloadPolicy.isMcz(name))continue;Source s=new Source();s.name=name;s.uri=DocumentsContract.buildDocumentUriUsingTree(tree,child);s.size=cursor.getLong(3);s.modified=cursor.getLong(4)/1000;rows.add(s);if(rows.size()>5000)throw new IOException("目录超过5000个谱面包，请选择子目录");
            }
            for(String folder:folders)walk(c,tree,folder,rows,depth+1,visited);
        }
    }
}
