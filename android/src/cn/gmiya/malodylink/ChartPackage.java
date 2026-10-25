package cn.gmiya.malodylink;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;

/** Validates imported packages and rebuilds a batch without extracting into game data. */
public final class ChartPackage {
    public static final int MAX_PACKAGES=5000,MAX_ENTRIES=20000,MAX_CHART_BYTES=16*1024*1024;
    public static final long MAX_PACKAGE_BYTES=256L*1024*1024,MAX_BATCH_BYTES=1024L*1024*1024;
    public interface Progress {void update(String message);}
    public static final class Item {
        public File file;public String name,digest;public int charts;public long expanded;public List<String> titles=new ArrayList<>();
    }
    public static boolean supported(String name){String n=name.toLowerCase(Locale.ROOT);return n.endsWith(".zip")||n.endsWith(".mcz")||n.endsWith(".mcb");}
    public static boolean preparedName(String name){return name!=null&&name.matches("batch-[a-f0-9-]{36}\\.(mcz|mcb)");}
    public static String path(String name)throws IOException {
        if(name==null||name.isEmpty()||name.length()>1024||name.startsWith("/")||name.indexOf('\\')>=0||name.indexOf(':')>=0)throw new IOException("谱面包包含不安全的路径");
        for(int i=0;i<name.length();i++)if(name.charAt(i)<32)throw new IOException("谱面包路径含控制字符");
        for(String part:name.split("/"))if(part.equals("..")||part.equals(".")||part.isEmpty())throw new IOException("谱面包目录不规范");
        return name;
    }
    private static void interrupted()throws IOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已停止处理");}
    public static String digest(File file)throws Exception {
        MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){interrupted();hash.update(b,0,n);}}
        StringBuilder s=new StringBuilder();for(byte b:hash.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
    public static Item inspect(File file,String name)throws Exception {
        if(!supported(name))throw new IOException("暂支持 .mcz / .mcb / .zip；RAR、7z 请先解压为谱面包");
        if(file.length()==0||file.length()>MAX_PACKAGE_BYTES)throw new IOException("文件为空或超过256MB");
        Item item=new Item();item.file=file;item.name=name;item.digest=digest(file);int count=0;Set<String> paths=new HashSet<>();
        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(new FileInputStream(file)))){
            ZipEntry e;byte[] b=new byte[65536];while((e=zip.getNextEntry())!=null){interrupted();if(++count>MAX_ENTRIES)throw new IOException("压缩包文件过多");
                String p=path(e.getName());if(!paths.add(p))throw new IOException("压缩包内有重复路径");if(e.isDirectory())continue;
                boolean chart=p.toLowerCase(Locale.ROOT).endsWith(".mc");ByteArrayOutputStream data=chart?new ByteArrayOutputStream():null;
                int n;while((n=zip.read(b))!=-1){interrupted();item.expanded+=n;if(item.expanded>MAX_BATCH_BYTES)throw new IOException("解压后超过1GB，已停止");
                    if(chart){if(data.size()+n>MAX_CHART_BYTES)throw new IOException("单张谱面过大");data.write(b,0,n);}}
                if(chart){JSONObject doc=new JSONObject(new String(data.toByteArray(),StandardCharsets.UTF_8));JSONObject meta=doc.optJSONObject("meta");
                    if(meta==null||!meta.has("mode")||!(doc.opt("note") instanceof JSONArray)||!(doc.opt("time") instanceof JSONArray))throw new IOException("包含无法识别的 .mc 谱面");
                    item.charts++;JSONObject song=meta.optJSONObject("song");String title=song==null?p:song.optString("title",p);if(!item.titles.contains(title)&&item.titles.size()<5)item.titles.add(title);
                }
            }
        }
        if(item.charts==0)throw new IOException("压缩包内未找到 Malody .mc 谱面");return item;
    }
    public static File bundle(List<Item> selected,File target,Progress progress)throws Exception {
        if(selected.isEmpty()||selected.size()>MAX_PACKAGES)throw new IOException("请选择1～5000个谱面包");
        boolean mcb=target.getName().toLowerCase(Locale.ROOT).endsWith(".mcb");
        if(!mcb&&(!target.getName().toLowerCase(Locale.ROOT).endsWith(".mcz")||selected.size()!=1))throw new IOException("多首歌曲必须使用 MCB 曲包，MCZ 只用于单个谱面包");
        Set<String> hashes=new HashSet<>();long total=0;for(Item i:selected){if(!hashes.add(i.digest))throw new IOException("所选谱面包存在重复文件");if(!digest(i.file).equals(i.digest))throw new IOException("源文件已变化，请重新选择");total+=i.expanded;if(total>MAX_BATCH_BYTES)throw new IOException("本次导入解压后超过1GB，请分批导入");}
        File stage=new File(target.getParentFile(),target.getName()+".partial");boolean complete=false;
        try{
            long written=0;int count=0;
            try(ZipOutputStream out=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(stage)))){
                out.setLevel(1);byte[] b=new byte[65536];for(int index=0;index<selected.size();index++){Item item=selected.get(index);progress.update("整理谱面包 "+(index+1)+" / "+selected.size()+" · "+item.name);
                    try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(new FileInputStream(item.file)))){
                        ZipEntry e;while((e=zip.getNextEntry())!=null){interrupted();if(++count>MAX_ENTRIES)throw new IOException("本次导入文件过多");String p=path(e.getName());if(e.isDirectory())continue;
                            // MCB scans each root song folder separately. Stable content-based names
                            // prevent unrelated batches from overwriting pack-1, pack-2, etc.
                            out.putNextEntry(new ZipEntry((mcb?"malody-link-"+item.digest+"/":"")+p));int n;while((n=zip.read(b))!=-1){interrupted();written+=n;if(written>MAX_BATCH_BYTES)throw new IOException("本次导入超过1GB，已停止");out.write(b,0,n);}out.closeEntry();
                        }
                    }
                }
            }
            if(target.exists()||!stage.renameTo(target))throw new IOException("导入包保存失败");complete=true;return target;
        }finally{if(!complete)stage.delete();}
    }
}
