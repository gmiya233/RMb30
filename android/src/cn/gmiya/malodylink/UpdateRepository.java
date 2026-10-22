package cn.gmiya.malodylink;

import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import java.io.*;
import java.util.*;
import java.util.function.IntConsumer;

public final class UpdateRepository {
    public static UpdateData check(Context c)throws Exception{return UpdateData.read(RankConnection.open(c).getPath("/_updates/latest.json",32000),RankConnection.config(c).getString("server"));}
    public static File download(Context c,UpdateData release,IntConsumer progress)throws Exception {
        File directory=new File(c.getCacheDir(),"updates");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("无法创建更新缓存");File target=new File(directory,release.digest+".apk"),temporary=new File(directory,release.digest+".part");
        if(target.isFile()&&target.length()==release.size)try(FileInputStream in=new FileInputStream(target)){if(CloudRecords.sha256(Engine.read(in,UpdateData.MAX_APK)).equals(release.digest)){verify(c,target,release);progress.accept(100);return target;}}
        try{RankConnection.open(c).downloadUpdate(release,temporary,progress);verify(c,temporary,release);if(!temporary.renameTo(target))throw new IOException("无法完成安装包保存");return target;}finally{temporary.delete();}
    }
    public static void verify(Context c,File file,UpdateData release)throws Exception {
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;PackageManager manager=c.getPackageManager();PackageInfo candidate=manager.getPackageArchiveInfo(file.getAbsolutePath(),flags),installed=manager.getPackageInfo(c.getPackageName(),flags);
        if(candidate==null||!c.getPackageName().equals(candidate.packageName)||candidate.versionCode!=release.code||!release.name.equals(candidate.versionName)||!signatures(candidate).equals(signatures(installed)))throw new IOException("安装包的应用、版本或签名不匹配");
    }
    private static Set<String> signatures(PackageInfo info)throws Exception {
        Signature[] source=Build.VERSION.SDK_INT>=28&&info.signingInfo!=null?info.signingInfo.getApkContentsSigners():info.signatures;if(source==null||source.length==0)throw new IOException("无法验证应用签名");Set<String> result=new HashSet<>();for(Signature signature:source)result.add(CloudRecords.sha256(signature.toByteArray()));return result;
    }
}
