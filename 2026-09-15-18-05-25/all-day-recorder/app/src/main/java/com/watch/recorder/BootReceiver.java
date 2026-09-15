package com.watch.recorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 开机自启录音 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            WatchdogReceiver.schedule(c);
            if (Prefs.autoStart(c)) {
                Intent s = new Intent(c, RecordService.class).setAction(RecordService.ACTION_START);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
                else c.startService(s);
            }
        }
    }
}
