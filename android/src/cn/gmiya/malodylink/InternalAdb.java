package cn.gmiya.malodylink;

import android.content.Context;
import android.os.Build;
import io.github.muntashirakon.adb.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.concurrent.*;
import javax.security.auth.x500.X500Principal;

/** On-device connection. Separate fixed read-only scopes for Malody and QQ package downloads. */
public final class InternalAdb extends AbsAdbConnectionManager {
    private static InternalAdb instance;
    private final PrivateKey key;
    private final Certificate cert;
    private static final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    public static synchronized InternalAdb get(Context c)throws Exception {if(instance==null)instance=new InternalAdb(c.getApplicationContext());return instance;}
    private InternalAdb(Context c)throws Exception {
        File privateFile=new File(c.getFilesDir(),"reader-private.key"),certFile=new File(c.getFilesDir(),"reader-certificate.der");
        if(privateFile.exists()&&certFile.exists()){
            key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Engine.read(new FileInputStream(privateFile),16384)));
            cert=CertificateFactory.getInstance("X.509").generateCertificate(new FileInputStream(certFile));
        }else{
            KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);java.security.KeyPair pair=generator.generateKeyPair();key=pair.getPrivate();
            cert=makeCertificate(pair);try(FileOutputStream out=c.openFileOutput(privateFile.getName(),Context.MODE_PRIVATE)){out.write(key.getEncoded());}
            try(FileOutputStream out=c.openFileOutput(certFile.getName(),Context.MODE_PRIVATE)){out.write(cert.getEncoded());}
        }
        cert.verify(cert.getPublicKey());setApi(Build.VERSION.SDK_INT);setHostAddress("127.0.0.1");setTimeout(12,TimeUnit.SECONDS);setThrowOnUnauthorised(true);
    }
    @Override protected PrivateKey getPrivateKey(){return key;}
    @Override protected Certificate getCertificate(){return cert;}
    @Override protected String getDeviceName(){return "Malody Link";}
    public boolean pairLocal(int port,String code)throws Exception {validPort(port);if(!code.matches("[0-9]{6}"))throw new IllegalArgumentException("请输入六位配对码");return pair("127.0.0.1",port,code);}
    public void connectLocal(int port)throws Exception {validPort(port);if(isConnected())return;disconnect();if(!connect("127.0.0.1",port))throw new IOException("连接未完成，请确认已配对且无线调试仍开启");}
    private static void validPort(int port){if(port<1||port>65535)throw new IllegalArgumentException("端口应在1～65535之间");}
    private static byte[] der(int tag,byte[]...parts)throws IOException {ByteArrayOutputStream body=new ByteArrayOutputStream();for(byte[] p:parts)body.write(p);byte[] b=body.toByteArray();ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(tag);if(b.length<128)out.write(b.length);else{int count=0;for(int n=b.length;n>0;n>>>=8)count++;out.write(128|count);for(int i=count-1;i>=0;i--)out.write(b.length>>>(8*i));}out.write(b);return out.toByteArray();}
    private static Certificate makeCertificate(java.security.KeyPair pair)throws Exception {
        byte[] algorithm=der(48,der(6,new byte[]{42,(byte)134,72,(byte)134,(byte)247,13,1,1,11}),der(5,new byte[0]));
        byte[] name=new X500Principal("CN=Malody Link").getEncoded();
        byte[] validity=der(48,der(23,"250101000000Z".getBytes(StandardCharsets.US_ASCII)),der(23,"490101000000Z".getBytes(StandardCharsets.US_ASCII)));
        byte[] tbs=der(48,der(160,der(2,new byte[]{2})),der(2,new BigInteger(120,new SecureRandom()).add(BigInteger.ONE).toByteArray()),algorithm,name,validity,name,pair.getPublic().getEncoded());
        Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(pair.getPrivate());signature.update(tbs);byte[] signed=signature.sign();byte[] bits=new byte[signed.length+1];System.arraycopy(signed,0,bits,1,signed.length);
        return CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der(48,tbs,algorithm,der(3,bits))));
    }
    static String allowedPath(String path){
        if(path==null||path.length()>4096)throw new SecurityException("无效读取路径");
        for(int i=0;i<path.length();i++)if(path.charAt(i)<32)throw new SecurityException("路径含控制字符");
        for(String part:path.split("/"))if(part.equals(".")||part.equals(".."))throw new SecurityException("不允许跳出Malody目录");
        String root=ReadOnlyService.ROOT,lower=path.toLowerCase(Locale.ROOT);
        boolean chart=path.startsWith(root+"/chart/")&&(lower.endsWith(".mc")||lower.endsWith(".jpg")||lower.endsWith(".jpeg")||lower.endsWith(".png")||lower.endsWith(".webp"));
        if(!chart&&!(path.startsWith(root+"/replay/")&&path.endsWith(".mrv")))throw new SecurityException("只允许读取Malody谱面、曲绘和回放");return path;
    }
    private static String quote(String s){return "'"+s.replace("'","'\"'\"'")+"'";}
    public static final class TransportException extends IOException {
        TransportException(String message,Throwable cause){super(message,cause);}
    }
    private static final class ReadFailure extends IOException {
        ReadFailure(String message){super(message);}
    }
    private static final class CommandResult {
        final byte[] stdout,stderr;final int exitCode;
        CommandResult(byte[] out,byte[] err,int code){stdout=out;stderr=err;exitCode=code;}
    }
    private byte[] command(String cmd,int limit)throws Exception {
        CommandResult result=commandResult(cmd,limit);
        if(result.exitCode!=0)throw new ReadFailure("本机文件读取失败："+new String(result.stderr,StandardCharsets.UTF_8));
        return result.stdout;
    }
    private CommandResult commandResult(String cmd,int limit)throws Exception {
        AdbConnection connection=getAdbConnection();
        if(connection==null||!isConnected())throw new TransportException("本机连接已断开",null);
        java.util.concurrent.atomic.AtomicBoolean timedOut=new java.util.concurrent.atomic.AtomicBoolean();
        ScheduledFuture<?> watchdog=timer.schedule(()->{timedOut.set(true);try{connection.close();}catch(Exception ignored){}},30,TimeUnit.SECONDS);
        try(AdbStream stream=openStream("shell,v2,raw:"+cmd);InputStream in=stream.openInputStream()){
            ByteArrayOutputStream stdout=new ByteArrayOutputStream(),stderr=new ByteArrayOutputStream();
            for(;;){
                int channel=in.read();if(channel<0)throw new EOFException("未收到文件读取结束标记");
                byte[] header=exact(in,4);long length=0;for(int i=0;i<4;i++)length|=(long)(header[i]&255)<<(8*i);
                if(length>33554432)throw new ReadFailure("读取数据过大");
                byte[] payload=exact(in,(int)length);
                if(channel==1){if(stdout.size()+payload.length>limit)throw new ReadFailure("文件超出读取上限");stdout.write(payload);}
                else if(channel==2){if(stderr.size()+payload.length>8192)throw new ReadFailure("文件读取错误信息过长");stderr.write(payload);}
                else if(channel==3){
                    if(payload.length!=1)throw new ReadFailure("文件读取结束标记无效");
                    return new CommandResult(stdout.toByteArray(),stderr.toByteArray(),payload[0]&255);
                }else throw new ReadFailure("无法识别的读取响应");
            }
        }catch(ReadFailure e){throw e;}
        catch(IOException e){throw new TransportException(timedOut.get()?"文件读取超时":"文件传输中断",e);}
        finally{watchdog.cancel(false);}
    }
    private static byte[] exact(InputStream in,int size)throws IOException {byte[] b=new byte[size];int offset=0;while(offset<size){int n=in.read(b,offset,size-offset);if(n<0)throw new EOFException("读取响应不完整");offset+=n;}return b;}
    public QqDownloadPolicy.Listing qqPackages(String directory)throws Exception {
        String root=QqDownloadPolicy.root(directory),q=quote(root);
        String cmd="[ -d "+q+" ] && [ -r "+q+" ] && [ -x "+q+" ] || exit 3; find "+q+
            " -maxdepth 6 \\( -type d \\( -name '.trooptmp' -o -name '.troopthumb' \\) -prune \\) -o \\( -type f -iname '*.mcz' -exec stat -c '%s:%Y:%Z:%n' {} + \\)";
        CommandResult result=commandResult(cmd,2000000);
        return QqDownloadPolicy.listing(new String(result.stdout,StandardCharsets.UTF_8),new String(result.stderr,StandardCharsets.UTF_8),result.exitCode,root);
    }
    public boolean qqDirectoryExists(String directory)throws Exception {String q=quote(QqDownloadPolicy.root(directory));return new String(command("if [ -d "+q+" ]; then printf yes; fi",16),StandardCharsets.US_ASCII).equals("yes");}
    /** Called only after the player's explicit cleanup confirmation. Never recursive. */
    public void removeQQPackage(QqDownloadPolicy.Entry source)throws Exception {
        String path=QqDownloadPolicy.file(source.path);if(source.stamp==null||!source.stamp.matches("[0-9]+:[0-9]+:[0-9]+"))throw new IOException("文件信息无效，请刷新目录");
        StringBuilder roots=new StringBuilder();for(String root:QqDownloadPolicy.ROOTS){if(roots.length()>0)roots.append('|');roots.append(quote(root)).append("/*");}
        String p=quote(path);String cmd="r=$(readlink -f "+p+") || exit 1; [ \"$r\" = "+p+" ] || exit 1; case \"$r\" in "+roots+") ;; *) exit 1;; esac; [ -f \"$r\" ] && [ ! -L \"$r\" ] || exit 1; [ \"$(stat -c '%s:%Y:%Z' \"$r\")\" = "+quote(source.stamp)+" ] || exit 1; rm -- \"$r\"";
        command(cmd,64);
    }
    public void copyQQPackage(QqDownloadPolicy.Entry source,File target)throws Exception {
        String path=QqDownloadPolicy.file(source.path);if(source.size<=0||source.size>ChartPackage.MAX_PACKAGE_BYTES||!source.stamp.matches("[0-9]+:[0-9]+:[0-9]+"))throw new IOException("文件为空、过大或信息无效");
        StringBuilder patterns=new StringBuilder();for(String root:QqDownloadPolicy.ROOTS){if(patterns.length()>0)patterns.append('|');patterns.append(quote(root)).append("/*");}
        String cmd="r=$(readlink -f "+quote(path)+") || exit 1; case \"$r\" in "+patterns+") ;; *) exit 1;; esac; [ \"$(stat -c '%s:%Y:%Z' \"$r\")\" = "+quote(source.stamp)+" ] || exit 1; cat \"$r\" || exit 1; [ \"$(stat -c '%s:%Y:%Z' \"$r\")\" = "+quote(source.stamp)+" ]";
        AdbConnection connection=getAdbConnection();if(connection==null||!isConnected())throw new TransportException("本机读取已断开",null);
        ScheduledFuture<?> watchdog=timer.schedule(()->{try{connection.close();}catch(Exception ignored){}},120,TimeUnit.SECONDS);boolean complete=false;
        try(AdbStream stream=openStream("shell,v2,raw:"+cmd);InputStream in=stream.openInputStream();OutputStream out=new BufferedOutputStream(new FileOutputStream(target))){
            long total=0;for(;;){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已停止复制");int channel=in.read();if(channel<0)throw new EOFException("文件读取未结束");byte[] header=exact(in,4);long length=0;for(int i=0;i<4;i++)length|=(long)(header[i]&255)<<(8*i);if(length>33554432)throw new IOException("读取响应过大");byte[] bytes=exact(in,(int)length);
                if(channel==1){total+=length;if(total>source.size||target.getUsableSpace()<4L*1024*1024)throw new IOException("文件大小已变化或存储空间不足");out.write(bytes);}
                else if(channel==3){if(bytes.length!=1||bytes[0]!=0||total!=source.size)throw new IOException("QQ 文件读取失败或下载尚未完成，请刷新后重试");complete=true;break;}
                else if(channel!=2)throw new IOException("读取响应无效");
            }
        }finally{watchdog.cancel(false);if(!complete)target.delete();}
    }
    public Engine.Reader reader(){return new Engine.Reader(){
        private final Map<String,String> stamps=new HashMap<>();
        public String fingerprint(String path){return stamps.getOrDefault(path, "");}
        public List<String> list(String type)throws Exception {
            if(!type.equals("chart")&&!type.equals("replay"))throw new SecurityException("无效目录");
            String dir=ReadOnlyService.ROOT+"/"+type,suffix=type.equals("chart")?"*.mc":"*.mrv";
            byte[] bytes=command("[ -d "+quote(dir)+" ] && find "+quote(dir)+" -maxdepth 13 -type f -name "+quote(suffix)+" -exec stat -c '%s:%Y:%Z:%n' {} +",8000000);
            List<String> paths=new ArrayList<>();
            for(String row:new String(bytes,StandardCharsets.UTF_8).split("\n")){
                if(row.isEmpty())continue;String[] fields=row.split(":",4);
                if(fields.length!=4||!fields[0].matches("[0-9]+")||!fields[1].matches("[0-9]+")||!fields[2].matches("[0-9]+"))throw new IOException("目录索引格式异常");
                String path=allowedPath(fields[3]);paths.add(path);stamps.put(path,fields[0]+":"+fields[1]+":"+fields[2]);
                if(paths.size()>20000)throw new IOException("文件数量超过读取上限");
            }return paths;
        }
        public byte[] file(String path)throws Exception {
            String p=quote(allowedPath(path)),root=quote(ReadOnlyService.ROOT);
            // Resolve on the shell side as well: metadata/symlinks cannot escape the allowlist.
            String cmd="r=$(readlink -f "+p+") || exit 1; case \"$r\" in "+root+"/chart/*|"+root+"/replay/*) ;; *) exit 1;; esac; n=$(stat -c %s \"$r\") || exit 1; [ \"$n\" -le 33554432 ] || exit 1; cat \"$r\"";
            return command(cmd,33554432);
        }
    };}
}
