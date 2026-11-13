package cn.gmiya.malodylink;

import java.io.IOException;
import java.util.*;

/** Only known download directories, never QQ accounts, messages, or databases. */
public final class QqDownloadPolicy {
    public static final class Directory {public String path,tree="",error="",warning="";public int count;public long newest;public boolean available(){return error.isEmpty();}}
    public interface Probe {Directory inspect(String root)throws Exception;}
    public static List<Directory> scan(Probe probe){
        List<Directory> result=new ArrayList<>();for(String root:ROOTS){try{Directory d=probe.inspect(root);if(d!=null)result.add(d);}catch(Exception e){Directory d=new Directory();d.path=root;d.error=e.getMessage()==null?"未能检查此目录":e.getMessage();result.add(d);}}
        sort(result);return result;
    }
    public static void sort(List<Directory> directories){directories.sort((a,b)->{int c=Boolean.compare(b.available(),a.available());if(c!=0)return c;c=Long.compare(b.newest,a.newest);return c!=0?c:Integer.compare(b.count,a.count);});}
    public static final String[] ROOTS={
        "/storage/emulated/0/Download/QQ",
        "/storage/emulated/0/Tencent/QQfile_recv",
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/QQfile_recv",
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/files/Tencent/QQfile_recv"
    };
    public static boolean isMcz(String name){return name!=null&&name.toLowerCase(Locale.ROOT).endsWith(".mcz");}
    public static void verifyRemoval(String expected,long size,long modified,String actual,long actualSize,long actualModified)throws IOException {
        if(!isMcz(expected)||!isMcz(actual)||!expected.equals(actual))throw new IOException("只能清理确认过的 MCZ 文件，请刷新目录");
        if(size>=0&&size!=actualSize||modified>0&&modified!=actualModified)throw new IOException("文件在确认后已变化，请刷新后重新选择清理");
    }
    public static String root(String value)throws IOException {for(String root:ROOTS)if(root.equals(value))return value;throw new IOException("不支持自动读取这个目录，请手动选择并授权目录");}
    public static String file(String path)throws IOException {
        if(path==null||path.length()>4096)throw new IOException("下载路径无效");
        for(int i=0;i<path.length();i++)if(path.charAt(i)<32)throw new IOException("下载路径包含控制字符");
        for(String part:path.split("/"))if(part.equals(".")||part.equals("..")||part.isEmpty()&&path.indexOf("//")>=0)throw new IOException("下载路径不规范");
        boolean under=false;for(String root:ROOTS)if(path.startsWith(root+"/"))under=true;
        if(!under||!isMcz(path))throw new IOException("只允许读取 QQ 下载目录中的 MCZ");return path;
    }
    public static final class Entry {public String path,stamp;public long size,modified;}
    public static final class Listing {
        public final List<Entry> entries;public final String warning;
        Listing(List<Entry> entries,String warning){this.entries=entries;this.warning=warning;}
    }
    /** Only child-directory permission failures allow partial results; all other failures stay fatal. */
    public static Listing listing(String text,String stderr,int exitCode,String root)throws IOException {
        root(root);String warning="",errors=stderr.trim();
        if(exitCode!=0||!errors.isEmpty()){
            if(exitCode!=1||errors.isEmpty())throw new IOException("下载目录读取失败，请检查路径和读取权限后重试");
            Set<String> denied=new HashSet<>();
            for(String line:errors.split("\n")){
                String prefix="find: ",suffix=": Permission denied";line=line.trim();
                if(!line.startsWith(prefix)||!line.endsWith(suffix))throw new IOException("下载目录扫描未完成，请刷新后重试");
                String path=line.substring(prefix.length(),line.length()-suffix.length());
                if(path.length()>1&&((path.startsWith("'")&&path.endsWith("'"))||(path.startsWith("\"")&&path.endsWith("\""))))path=path.substring(1,path.length()-1);
                if(!path.startsWith(root+"/")||path.contains("/../")||path.contains("/./"))throw new IOException("下载目录没有读取权限，请手动授权或更换路径");
                denied.add(path);
            }
            warning="扫描不完整："+denied.size()+" 个子目录无读取权限，仅显示可读取的 MCZ";
        }
        return new Listing(parse(text,root),warning);
    }
    public static List<Entry> parse(String text,String root)throws IOException {
        root(root);Map<String,Entry> entries=new HashMap<>();
        for(String row:text.split("\n")){if(row.isEmpty())continue;String[] f=row.split(":",4);if(f.length!=4||!f[0].matches("[0-9]+")||!f[1].matches("[0-9]+")||!f[2].matches("[0-9]+"))throw new IOException("下载目录索引格式异常");
            Entry e=new Entry();e.path=file(f[3]);if(!e.path.startsWith(root+"/"))throw new IOException("下载文件超出所选目录");
            try{e.size=Long.parseLong(f[0]);e.modified=Long.parseLong(f[1]);}catch(NumberFormatException invalid){throw new IOException("下载文件信息无效");}e.stamp=f[0]+":"+f[1]+":"+f[2];entries.put(e.path,e);if(entries.size()>5000)throw new IOException("目录超过5000个谱面包，请手动选择子目录");
        }
        List<Entry> result=new ArrayList<>(entries.values());result.sort((a,b)->{int c=Long.compare(b.modified,a.modified);return c!=0?c:a.path.compareTo(b.path);});return result;
    }
}
