package cn.gmiya.malodylink;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.util.*;

/** Debug-APK checks using a sample supplied by the USB harness, not bundled data. */
public final class SelfTestInstrumentation extends Instrumentation {
    private void check(boolean value,String name)throws Exception {if(!value)throw new Exception("FAIL: "+name);}
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){Bundle result=new Bundle();
        try{
            Context context=getTargetContext();Engine e=new Engine(context);
            byte[] sample=Engine.read(new FileInputStream(new File(context.getFilesDir(),"test-sample.mrv")),33554432);
            e.importUsb();if(e.scores.length()==0)e.importReplay(sample);
            check(e.scores.length()==1,"one real matching score stored");
            check(e.scores.getJSONObject(0).getInt("cid")==600065,"mapped to server CID 600065");
            check(!e.importReplay(sample)&&e.scores.length()==1,"same replay cannot be imported twice");
            byte[] unknown=new byte[34];unknown[0]=42;unknown[1]=32;Arrays.fill(unknown,2,34,(byte)'0');
            check(!e.importReplay(unknown)&&e.scores.length()==1,"unknown chart rejected before score payload is accessed");
            String h=Proto.replayHash(sample);JSONObject alias=e.aliases.getJSONObject(h);String old=alias.getString("server_hash");
            alias.put("server_hash","obsolete-version");check(!e.importReplay(sample),"stale chart revision rejected");alias.put("server_hash",old);
            JSONObject oldCatalog=e.catalog;e.catalog=new JSONObject().put("charts",new JSONArray());
            check(e.export().getJSONArray("scores").length()==0,"removed server chart excluded from export");e.catalog=oldCatalog;
            check(!e.scores.getJSONObject(0).getBoolean("uploaded"),"no automatic score upload");
            result.putString("stream","PASS: 7 device checks; matched CID 600065; unknown/stale charts rejected; duplicate and export rules verified.\n");
            finish(-1,result);
        }catch(Throwable t){result.putString("stream","FAIL: "+t.toString()+"\n");finish(0,result);}
    }
}
