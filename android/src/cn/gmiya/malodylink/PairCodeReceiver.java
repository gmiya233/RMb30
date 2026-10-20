package cn.gmiya.malodylink;

import android.app.RemoteInput;
import android.content.*;
import android.os.Bundle;

public final class PairCodeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){Bundle input=RemoteInput.getResultsFromIntent(intent);if(input==null)return;CharSequence code=input.getCharSequence(PairingService.CODE);if(code==null)return;context.startService(new Intent(context,PairingService.class).setAction("PAIR").putExtra(PairingService.CODE,code.toString()));}
}
