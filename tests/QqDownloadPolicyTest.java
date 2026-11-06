import cn.gmiya.malodylink.QqDownloadPolicy;
import java.io.IOException;
import java.util.*;

public final class QqDownloadPolicyTest {
    private static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);System.out.println("PASS: "+name);}
    private interface Task {void run()throws Exception;}
    private static void reject(Task action,String message)throws Exception{try{action.run();throw new AssertionError(message);}catch(IOException expected){System.out.println("PASS: "+message);}}
    public static void main(String[] args)throws Exception {
        String root=QqDownloadPolicy.ROOTS[0];
        List<QqDownloadPolicy.Entry> rows=QqDownloadPolicy.parse("100:10:11:"+root+"/old.mcz\n200:30:31:"+root+"/sub/新谱:包.MCZ\n100:10:11:"+root+"/old.mcz\n",root);
        check(rows.size()==2&&rows.get(0).size==200&&rows.get(0).modified==30,"metadata rows deduplicate and newest downloads sort first");
        check(rows.get(0).path.endsWith("新谱:包.MCZ")&&rows.get(0).stamp.equals("200:30:31"),"Chinese names, colons and original stamps are preserved");
        check(QqDownloadPolicy.parse("",root).isEmpty(),"empty directory is a valid empty list");
        String visible="100:10:11:"+root+"/new.MCZ\n";
        String denied="find: "+root+"/private: Permission denied\nfind: "+root+"/cache: Permission denied\n";
        QqDownloadPolicy.Listing readable=QqDownloadPolicy.listing(visible,denied,1,root);
        check(readable.entries.size()==1&&readable.warning.contains("2 个子目录"),"child permission errors preserve visible MCZ and report incomplete scan");
        check(QqDownloadPolicy.listing("",denied,1,root).warning.contains("扫描不完整"),"empty partial scan never claims a completely empty folder");
        check(QqDownloadPolicy.listing("","",0,root).warning.isEmpty(),"successful empty scan has no warning");
        reject(()->QqDownloadPolicy.listing("","find: "+root+": Permission denied",1,root),"root permission failure is not a usable empty directory");
        reject(()->QqDownloadPolicy.listing(visible,denied,3,root),"failed root preflight remains fatal despite stdout");
        reject(()->QqDownloadPolicy.listing(visible,"find: "+root+"/gone: No such file or directory",1,root),"non-permission scan errors remain fatal");
        reject(()->QqDownloadPolicy.listing(visible,"",1,root),"nonzero exit without diagnostics is not treated as success");
        reject(()->QqDownloadPolicy.listing(visible,denied,0,root),"unexpected diagnostics are not silently ignored");
        reject(()->QqDownloadPolicy.listing("truncated",denied,1,root),"corrupt partial index is rejected");
        reject(()->QqDownloadPolicy.listing("100:10:11:"+root+"/other.zip",denied,1,root),"partial scan cannot introduce non-MCZ files");
        check(QqDownloadPolicy.isMcz("谱面.MCZ")&&QqDownloadPolicy.isMcz("song.McZ")&&!QqDownloadPolicy.isMcz("song.zip")&&!QqDownloadPolicy.isMcz("song.mcz.part"),"detection accepts MCZ only, case insensitive, excluding ZIP and partial downloads");
        QqDownloadPolicy.verifyRemoval("song.MCZ",100,20,"song.MCZ",100,20);
        reject(()->QqDownloadPolicy.verifyRemoval("song.zip",100,20,"song.zip",100,20),"cleanup refuses ZIP files");
        reject(()->QqDownloadPolicy.verifyRemoval("song.MCZ",100,20,"other.MCZ",100,20),"cleanup refuses renamed files");
        reject(()->QqDownloadPolicy.verifyRemoval("song.MCZ",100,20,"song.MCZ",101,20),"cleanup refuses files changed after confirmation");
        List<QqDownloadPolicy.Directory> offline=QqDownloadPolicy.scan(path->{if(!path.equals(root))throw new IOException("wireless debugging off");QqDownloadPolicy.Directory d=new QqDownloadPolicy.Directory();d.path=path;d.count=52;d.tree="granted-folder";d.newest=100;return d;});
        check(offline.size()==QqDownloadPolicy.ROOTS.length&&offline.get(0).available()&&offline.get(0).count==52,"granted readable directory survives failures of all restricted paths");
        check(offline.stream().filter(QqDownloadPolicy.Directory::available).count()==1&&offline.stream().filter(d->!d.available()).allMatch(d->!d.error.isEmpty()),"unverified candidate paths are never reported as readable empty folders");
        List<QqDownloadPolicy.Directory> partial=QqDownloadPolicy.scan(path->{if(path.equals(root))throw new IOException("one directory broken");QqDownloadPolicy.Directory d=new QqDownloadPolicy.Directory();d.path=path;d.count=3;return d;});
        check(partial.get(0).available()&&partial.stream().filter(QqDownloadPolicy.Directory::available).count()==3,"one path failure does not abort remaining directory checks");
        for(String r:QqDownloadPolicy.ROOTS)check(QqDownloadPolicy.file(r+"/song.mcz").startsWith(r),"known QQ download root allowed");
        for(String p:Arrays.asList(root+"/../secret.zip",root+"//song.zip",root+"/./song.zip",root+"/message.db",root+"/reader.key","/data/data/com.tencent.mobileqq/databases/message.zip","/storage/emulated/0/Android/data/com.tencent.mobileqq/files/secret.zip",root+"/injected\nfile.zip"))reject(()->QqDownloadPolicy.file(p),"non-download or unsafe path rejected");
        reject(()->QqDownloadPolicy.root(root+"/../"),"auto detection cannot accept arbitrary shell paths");
        reject(()->QqDownloadPolicy.parse("100:10:11:"+QqDownloadPolicy.ROOTS[1]+"/other.zip",root),"listing cannot silently escape chosen directory");
        reject(()->QqDownloadPolicy.parse("oops:10:11:"+root+"/old.mcz",root),"bad metadata is not treated as an empty directory");
        reject(()->QqDownloadPolicy.parse("999999999999999999999999999:10:11:"+root+"/old.mcz",root),"overflowing sizes rejected");
    }
}
