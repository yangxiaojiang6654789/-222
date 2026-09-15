package com.watch.recorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 充电 / 电量恢复正常时触发上传 */
public class ChargeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_POWER_CONNECTED.equals(a) || Intent.ACTION_BATTERY_OKAY.equals(a)) {
            if (!Prefs.autoUpload(c)) return;
            Intent s = new Intent(c, UploadService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
            else c.startService(s);
        }
    }
}
