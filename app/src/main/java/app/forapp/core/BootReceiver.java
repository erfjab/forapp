package app.forapp.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a restart or an app update, picks the queue back up if anything is still waiting. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Forwarder.schedule(context.getApplicationContext());
    }
}
