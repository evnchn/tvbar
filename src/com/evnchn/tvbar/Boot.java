package com.evnchn.tvbar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class Boot extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        c.startActivity(new Intent(c, Launch.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
