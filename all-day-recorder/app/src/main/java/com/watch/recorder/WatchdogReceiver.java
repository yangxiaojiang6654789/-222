package com.watch.recorder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;

/**
 * 看门狗：定时自检（默认每 5 分钟一次）。
 * 1. 录音服务被 Noemie OS 杀后台后自动拉起（息屏录音自愈）
 * 2. 顺带做充电上传重试
 */
public class WatchdogReceiver extends BroadcastReceiver {

    public static final String ACTION_TICK = "com.watch.recorder.WATCHDOG";

    @Override
    public void onReceive(Context c, Intent intent) {
        // 1. 自愈：标记为录音中（未暂停）则确保服务在跑
        if (Prefs.isRecording(c) && !Prefs.isPaused(c)) {
            Intent s = new Intent(c, RecordService.class).setAction(RecordService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(s);
            else c.startService(s);
        }
        // 2. 上传重试：有待上传 && 充电中 && 自动上传开启
        if (Prefs.autoUpload(c) && isCharging(c)) {
            File[] pending = RecorderFiles.pending(c);
            if (pending.length > 0) {
                Intent u = new Intent(c, UploadService.class);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(u);
                else c.startService(u);
            }
        }
    }

    private boolean isCharging(Context c) {
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return false;
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    /** 启动 / 重设看门狗闹钟（每 5 分钟一次，可重复调用） */
    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(c, WatchdogReceiver.class).setAction(ACTION_TICK);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1001, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + 60_000L, 5 * 60 * 1000L, pi);
    }
}
