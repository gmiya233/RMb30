package cn.gmiya.malodylink;

import android.app.Instrumentation;
import android.content.*;
import android.os.Bundle;
import io.github.muntashirakon.crypto.spake2.*;
import org.conscrypt.Conscrypt;
import java.io.*;
import java.security.*;
import java.util.*;

public final class InternalReaderTestInstrumentation extends Instrumentation {
    private int port;private boolean stressOnly;private String username,password;
    @Override public void onCreate(Bundle b){super.onCreate(b);if(b!=null){stressOnly="true".equals(b.getString("stress_only"));try{port=Integer.parseInt(b.getString("port","0"));}catch(Exception ignored){}username=b.getString("username");password=b.getString("password");b.remove("password");}start();}
    private android.view.View find(android.view.View v,String text){if(v instanceof android.widget.TextView&&text.contentEquals(((android.widget.TextView)v).getText()))return v;if(v instanceof android.view.ViewGroup){android.view.ViewGroup g=(android.view.ViewGroup)v;for(int i=0;i<g.getChildCount();i++){android.view.View hit=find(g.getChildAt(i),text);if(hit!=null)return hit;}}return null;}
    private void check(boolean ok,String text)throws Exception {if(!ok)throw new Exception(text);}
    private void progress(String text){Bundle b=new Bundle();b.putString("step",text);sendStatus(1,b);}
    @Override public void onStart(){Bundle result=new Bundle();int status=-1;
        try{
            // Keep the tested app visible: vivo suspends background-only test processes and their NSD callbacks.
            ActivityMonitor launcher=addMonitor(MainActivity.class.getName(),null,false);
            try(android.os.ParcelFileDescriptor launched=getUiAutomation().executeShellCommand("am start --activity-clear-top -n cn.gmiya.malodylink/.MainActivity")){Engine.read(new FileInputStream(launched.getFileDescriptor()),64000);}
            android.app.Activity home=waitForMonitorWithTimeout(launcher,5000);removeMonitor(launcher);check(home!=null,"home visible");
            progress("native components");
            check(Conscrypt.isAvailable(),"bundled TLS native provider loads");check(javax.net.ssl.SSLContext.getInstance("TLSv1.3",Conscrypt.newProvider())!=null,"TLS 1.3 available");
            byte[] alice="alice".getBytes("UTF-8"),bob="bob".getBytes("UTF-8"),fixturePassword="test-fixture-only".getBytes("UTF-8");
            Spake2Context a=new Spake2Context(Spake2Role.Alice,alice,bob),b=new Spake2Context(Spake2Role.Bob,bob,alice);
            try{byte[] ma=a.generateMessage(fixturePassword),mb=b.generateMessage(fixturePassword);check(Arrays.equals(a.processMessage(mb),b.processMessage(ma)),"native pairing key agreement");}finally{a.destroy();b.destroy();}
            Context context=getTargetContext();InternalAdb adb=InternalAdb.get(context);Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(adb.getPrivateKey());signer.update(fixturePassword);byte[] signed=signer.sign();signer.initVerify(adb.getCertificate());signer.update(fixturePassword);check(signer.verify(signed),"persisted client identity matches certificate");
            String root=ReadOnlyService.ROOT;
            check(InternalAdb.allowedPath(root+"/chart/test/测试's.mc").endsWith(".mc"),"Unicode and quote chart names allowed");
            for(String denied:new String[]{"/sdcard/other/file.mc",root+"/chart/../score.bin",root+"/score.bin",root+"/chart/x.txt",root+"/replay/x.mrv\ncat /etc/passwd"}){
                boolean rejected=false;try{InternalAdb.allowedPath(denied);}catch(SecurityException e){rejected=true;}check(rejected,"reject outside scope");
            }
            if(port!=0){progress("discover local port");int actualPort=port<0?LocalAdbDiscovery.find(context,LocalAdbDiscovery.CONNECT,6000):port;adb.connectLocal(actualPort);progress("local TLS connected");Engine.Reader reader=adb.reader();List<String> charts=reader.list("chart"),replays=reader.list("replay");check(!charts.isEmpty()&&!replays.isEmpty(),"real Malody chart/replay listing");progress("chart and replay listing");
                if(username!=null&&password!=null){Engine login=new Engine(context);try{login.connect(login.server,username,password,p->{});}finally{username=null;password=null;}}
                String unicode=null;for(String chart:charts)if(!chart.matches("[\\x00-\\x7F]*")){unicode=chart;break;}check(unicode!=null,"real Unicode chart fixture exists");check(reader.file(unicode).length>0,"Chinese path reads without BufferOverflow");
                progress("Unicode chart file read");byte[] replay=reader.file(replays.get(0));check(Proto.replayHash(replay).length()==32,"raw binary replay parses over built-in connection");progress("binary replay read");
                Collections.sort(replays);
                for(int i=0;i<Math.min(3,replays.size());i++){
                    String path=replays.get(i);check(path.matches("[A-Za-z0-9_./-]+"),"independent fixture path is a single safe argument");byte[] expected;
                    try(android.os.ParcelFileDescriptor shell=getUiAutomation().executeShellCommand("cat "+path)){
                        expected=Engine.read(new FileInputStream(shell.getFileDescriptor()),33554432);
                    }
                    check(expected.length>0,"independent sample is not empty");
                    for(int repeat=0;repeat<10;repeat++)check(Arrays.equals(expected,reader.file(path)),"complete binary read matches independent shell sample");
                }
                progress("30 repeated binary reads match independent shell byte-for-byte");
                Engine.Reader resilient=LocalScoreSync.recovering(context,adb,this::progress);
                adb.disconnect();check(Proto.replayHash(resilient.file(replays.get(0))).length()==32,"recover a dropped connection without re-pairing");
                progress("forced connection loss recovered");
                if(stressOnly){result.putString("stream","PASS: 30 binary reads match independent shell; forced disconnect recovered without pairing.\n");finish(-1,result);return;}
                String scanned=LocalScoreSync.run(context,0,this::progress);Engine engine=new Engine(context);check(engine.scores.length()>=1,"allowlisted real score remains readable");check(!LocalScoreSync.isBusy()&&context.getSharedPreferences("reader",0).getBoolean("last_ok",false),"shared sync completes and records success");progress("first scan and default catalogue complete");
                byte[] before=Engine.read(new FileInputStream(new File(context.getFilesDir(),"scores.json")),16000000);
                Engine.Reader source=adb.reader();int[] replayReads={0};Engine.Reader counting=new Engine.Reader(){
                    public List<String> list(String type)throws Exception{return source.list(type);}
                    public String fingerprint(String path)throws Exception{return source.fingerprint(path);}
                    public byte[] file(String path)throws Exception{if(path.endsWith(".mrv"))replayReads[0]++;return source.file(path);}
                };
                engine.scan(counting,p->{});check(replayReads[0]==0,"unchanged replay bodies are not downloaded again");
                progress("incremental scan read zero unchanged replay bodies");
                result.putString("device",scanned+"; binary replay "+replay.length+" bytes; raw score stable="+Arrays.equals(before,Engine.read(new FileInputStream(new File(context.getFilesDir(),"scores.json")),16000000)));adb.disconnect();
                long last=context.getSharedPreferences("reader",0).getLong("last_sync",0);
                ActivityMonitor foreground=addMonitor(MainActivity.class.getName(),null,false);
                try(android.os.ParcelFileDescriptor opened=getUiAutomation().executeShellCommand("am start --activity-clear-top -n cn.gmiya.malodylink/.MainActivity")){Engine.read(new FileInputStream(opened.getFileDescriptor()),64000);}
                android.app.Activity main=waitForMonitorWithTimeout(foreground,5000);removeMonitor(foreground);check(main!=null,"home returns to foreground");waitForIdleSync();
                ActivityMonitor monitor=addMonitor(InternalReaderActivity.class.getName(),null,false);
                runOnMainSync(()->{android.view.View sync=find(main.getWindow().getDecorView(),"同步本机成绩");if(sync==null)throw new AssertionError("home sync button missing");sync.performClick();});
                android.app.Activity page=waitForMonitorWithTimeout(monitor,5000);removeMonitor(monitor);check(page!=null,"home opens reader with one tap");
                waitForIdleSync();runOnMainSync(()->{android.view.View manual=find(page.getWindow().getDecorView(),"手动输入连接端口");if(manual==null||manual.isShown())throw new AssertionError("advanced controls must start collapsed");});
                try(android.os.ParcelFileDescriptor background=getUiAutomation().executeShellCommand("input keyevent 3")){Engine.read(new FileInputStream(background.getFileDescriptor()),64000);}
                for(int i=0;i<900&&context.getSharedPreferences("reader",0).getLong("last_sync",0)<=last;i++)Thread.sleep(200);
                check(context.getSharedPreferences("reader",0).getLong("last_sync",0)>last&&context.getSharedPreferences("reader",0).getBoolean("last_ok",false),"one tap discovers, reconnects and imports");
                runOnMainSync(()->page.finish());
                result.putString("ui","PASS: home one-tap foreground sync reconnects and completes after HOME; advanced controls collapsed");
            }
            result.putString("stream","PASS: bundled TLS, native pairing, persistent identity, read-only path scope"+(port!=0?", real local connection, Unicode file and Malody reads":" (system pairing/connection not yet tested)")+".\n");
        }catch(Throwable e){status=0;StringWriter trace=new StringWriter();e.printStackTrace(new PrintWriter(trace));result.putString("stream","FAIL: "+trace+"\n");}
        finish(status,result);
    }
}
