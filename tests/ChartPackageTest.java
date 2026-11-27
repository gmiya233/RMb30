import cn.gmiya.malodylink.ChartPackage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class ChartPackageTest {
    private static byte[] chart(String title){return ("{\"meta\":{\"mode\":7,\"song\":{\"title\":\""+title+"\"},\"song_file\":\"song.ogg\"},\"time\":[],\"note\":[]}").getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);System.out.println("PASS: "+name);}
    private static File zip(Path root,String name,Map<String,byte[]> files)throws Exception{
        File f=root.resolve(name).toFile();try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(f))){for(Map.Entry<String,byte[]> e:files.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}}return f;
    }
    private interface Task{void run()throws Exception;}
    private static void reject(Task t,String name)throws Exception{try{t.run();throw new AssertionError(name+" was accepted");}catch(IOException|org.json.JSONException expected){System.out.println("PASS: "+name);}}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get("build/chart-package-tests");Files.createDirectories(root);root=Files.createTempDirectory(root,"fixture-");
        Map<String,byte[]> first=new LinkedHashMap<>();first.put("song.mc",chart("One"));first.put("song.ogg",new byte[]{1,2,3});first.put("cover.jpg",new byte[]{7,8});
        Map<String,byte[]> second=new LinkedHashMap<>();second.put("song.mc",chart("Two"));second.put("song.ogg",new byte[]{4,5,6});
        File a=zip(root,"one.zip",first),b=zip(root,"two.mcz",second);ChartPackage.Item ai=ChartPackage.inspect(a,"one.zip"),bi=ChartPackage.inspect(b,"two.mcz");
        check(ai.charts==1&&ai.titles.equals(Arrays.asList("One")),"valid package reports chart count and title");
        File merged=ChartPackage.bundle(Arrays.asList(ai,bi),root.resolve("batch.mcb").toFile(),s->{});
        try(ZipFile z=new ZipFile(merged)){
            check(z.getEntry("malody-link-"+ai.digest+"/song.mc")!=null&&z.getEntry("malody-link-"+bi.digest+"/song.mc")!=null,"same filenames in different packages cannot collide");
            check(Arrays.equals(read(z.getInputStream(z.getEntry("malody-link-"+ai.digest+"/song.ogg"))),first.get("song.ogg"))&&Arrays.equals(read(z.getInputStream(z.getEntry("malody-link-"+bi.digest+"/song.ogg"))),second.get("song.ogg")),"each chart retains its own relative audio and original bytes");
        }
        check(ChartPackage.inspect(merged,"batch.mcb").charts==2,"MCB contains both charts under separate song roots");
        Set<String> songRoots=new HashSet<>();try(ZipFile z=new ZipFile(merged)){Enumeration<? extends ZipEntry> entries=z.entries();while(entries.hasMoreElements()){String entry=entries.nextElement().getName();check(entry.contains("/"),"MCB files are never placed directly at bundle root");songRoots.add(entry.substring(0,entry.indexOf('/')));}}
        check(songRoots.size()==2,"two input songs have two distinct top-level MCB directories");
        final Path outputRoot=root;
        reject(()->ChartPackage.bundle(Arrays.asList(ai,bi),outputRoot.resolve("wrong-multi-song.mcz").toFile(),s->{}),"multiple songs cannot accidentally be exported as a single-song MCZ");
        File separate=ChartPackage.bundle(Arrays.asList(bi),root.resolve("later-batch.mcb").toFile(),s->{});
        try(ZipFile z=new ZipFile(separate)){check(z.getEntry("malody-link-"+bi.digest+"/song.mc")!=null,"song directory is stable when imported in a different batch position");}
        check(ChartPackage.preparedName("batch-00000000-0000-0000-0000-000000000000.mcb")&&ChartPackage.preparedName("batch-00000000-0000-0000-0000-000000000000.mcz")&&!ChartPackage.preparedName("batch-00000000-0000-0000-0000-000000000000.mcb.partial")&&!ChartPackage.preparedName("../outside.mcb"),"provider filename policy supports MCB and MCZ but refuses unfinished and outside files");
        List<ChartPackage.Item> many=new ArrayList<>();
        for(int n=0;n<40;n++){
            Map<String,byte[]> files=new LinkedHashMap<>();files.put("song.mc",chart("Batch "+n));files.put("song.ogg",new byte[]{(byte)n});
            File source=zip(root,"source-"+n+".mcz",files);many.add(ChartPackage.inspect(source,source.getName()));
        }
        File all=ChartPackage.bundle(many,root.resolve("all-selected.mcb").toFile(),s->{});
        check(ChartPackage.inspect(all,"all-selected.mcb").charts==40,"one MCB contains all 40 selected charts beyond the previous 32-package cap");
        try(ZipFile z=new ZipFile(all)){
            for(int n=0;n<40;n++)check(Arrays.equals(read(z.getInputStream(z.getEntry("malody-link-"+many.get(n).digest+"/song.ogg"))),new byte[]{(byte)n}),"batch chart "+n+" keeps its own identically named audio");
        }
        long originalExpanded=many.get(0).expanded;many.get(0).expanded=ChartPackage.MAX_BATCH_BYTES+1;
        final Path manyRoot=root;
        reject(()->ChartPackage.bundle(many,manyRoot.resolve("oversized.mcb").toFile(),s->{}),"all-selected batch still respects the expanded-size limit");
        many.get(0).expanded=originalExpanded;
        File single=ChartPackage.bundle(Arrays.asList(ai),root.resolve("single.mcz").toFile(),s->{});try(ZipFile z=new ZipFile(single)){check(z.getEntry("song.mc")!=null&&z.getEntry("song.ogg")!=null&&z.getEntry("malody-link-"+ai.digest+"/song.mc")==null,"single-package compatibility mode keeps original root layout");}
        final Path dir=root;
        reject(()->ChartPackage.bundle(Arrays.asList(ai,ai),dir.resolve("duplicate.mcz").toFile(),s->{}),"identical source packages rejected");
        reject(()->ChartPackage.inspect(a,"one.rar"),"unsupported archives rejected clearly");
        File noChart=zip(root,"no-chart.zip",Collections.singletonMap("song.ogg",new byte[]{1}));reject(()->ChartPackage.inspect(noChart,"no-chart.zip"),"audio-only ZIP rejected");
        File bad=zip(root,"bad.zip",Collections.singletonMap("bad.mc","{}".getBytes(StandardCharsets.UTF_8)));reject(()->ChartPackage.inspect(bad,"bad.zip"),"invalid MC JSON structure rejected");
        File text=root.resolve("fake.zip").toFile();Files.write(text.toPath(),"not a zip".getBytes(StandardCharsets.UTF_8));reject(()->ChartPackage.inspect(text,"fake.zip"),"fake ZIP content rejected");
        for(String name:Arrays.asList("../escape.mc","/absolute.mc","a/../../escape.mc","C:/windows.mc","a\\escape.mc","a//escape.mc"))reject(()->ChartPackage.path(name),"unsafe path rejected: "+name);
        File nested=zip(root,"nested.zip",Collections.singletonMap("folder/谱面.mc",chart("Nested")));check(ChartPackage.inspect(nested,"nested.zip").charts==1,"nested and Chinese chart paths supported");
        try{ChartPackage.bundle(Arrays.asList(ai),dir.resolve("cancel.mcz").toFile(),s->{throw new IllegalStateException("interrupted");});throw new AssertionError("interruption was ignored");}catch(IllegalStateException expected){}
        check(!Files.exists(dir.resolve("cancel.mcz"))&&!Files.exists(dir.resolve("cancel.mcz.partial")),"interrupted preparation leaves no complete or partial output");
        Files.write(a.toPath(),new byte[]{1,2,3});reject(()->ChartPackage.bundle(Arrays.asList(ai),dir.resolve("changed.mcz").toFile(),s->{}),"changed source package cannot bypass inspection");
    }
    private static byte[] read(InputStream input)throws Exception{try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
}
