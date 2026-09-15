package com.watch.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.MediaRecorder;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * 全天录音前台服务。
 * 支持：开始 / 暂停 / 继续 / 停止，音质与分段时长可配置，
 * 前台通知 + 部分唤醒锁对抗 Noemie OS 杀后台，累计时长实时写入 Prefs。
 */
public class RecordService extends Service {

    public static final String ACTION_START = "com.watch.recorder.START";
    public static final String ACTION_STOP = "com.watch.recorder.STOP";
    public static final String ACTION_PAUSE = "com.watch.recorder.PAUSE";
    public static final String ACTION_RESUME = "com.watch.recorder.RESUME";

    private static final String CHANNEL_ID = "record_channel";
    private static final int NOTIF_ID = 1001;

    private MediaRecorder recorder;
    private PowerManager.WakeLock wakeLock;
    private boolean recording = false;
    private boolean paused = false;
    private int tickCount = 0;
    private long lastStorageWarn = 0;
    private BroadcastReceiver batteryReceiver;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (recording && !paused) {
                int s = Prefs.accumSeconds(RecordService.this) + 1;
                Prefs.putInt(RecordService.this, Prefs.KEY_ACCUM_SECONDS, s);
                tickCount++;
                if (tickCount % 30 == 0) checkStorage();
                refreshNotification();
                handler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        registerBattery();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            doStop();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            pauseRecording();
            return START_STICKY;
        }
        if (ACTION_RESUME.equals(action)) {
            resumeRecording();
            return START_STICKY;
        }
        // 启动（或系统重建：intent 为 null 时按持久化状态恢复）
        if (!recording) {
            if (intent == null && Prefs.isRecording(this) && Prefs.isPaused(this)) {
                // 之前是暂停态：恢复为暂停前台状态，不录音
                recording = true;
                paused = true;
                acquireWakeLock();
                startForeground(NOTIF_ID, buildNotification());
                refreshNotification();
            } else {
                beginSession();
            }
        }
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "录音中", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private File recordingDir() {
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MUSIC), "recordings");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    // ---- 会话控制 ----

    private void beginSession() {
        Prefs.putInt(this, Prefs.KEY_ACCUM_SECONDS, 0);
        recording = true;
        paused = false;
        Prefs.putBool(this, Prefs.KEY_RECORDING, true);
        Prefs.putBool(this, Prefs.KEY_PAUSED, false);
        acquireWakeLock();
        startForeground(NOTIF_ID, buildNotification());
        if (!startSegment()) {
            // 录音启动失败，回滚状态
            recording = false;
            Prefs.putBool(this, Prefs.KEY_RECORDING, false);
            stopForeground(true);
            stopSelf();
            return;
        }
        startTick();
        refreshNotification();
    }

    private void pauseRecording() {
        if (!recording || paused) return;
        stopSegment();
        paused = true;
        Prefs.putBool(this, Prefs.KEY_PAUSED, true);
        stopTick();
        refreshNotification();
    }

    private void resumeRecording() {
        if (!recording || !paused) return;
        paused = false;
        Prefs.putBool(this, Prefs.KEY_PAUSED, false);
        if (!startSegment()) {
            // 恢复失败视为停止
            doStop();
            return;
        }
        startTick();
        refreshNotification();
    }

    private void doStop() {
        stopSegment();
        recording = false;
        paused = false;
        stopTick();
        releaseWakeLock();
        Prefs.putBool(this, Prefs.KEY_RECORDING, false);
        Prefs.putBool(this, Prefs.KEY_PAUSED, false);
        stopForeground(true);
        stopSelf();
    }

    // ---- 录音段 ----

    /** 开始录制一段，成功返回 true */
    private boolean startSegment() {
        int quality = Prefs.quality(this);
        long segmentMs = Prefs.segmentMinutes(this) * 60L * 1000L;

        String name = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".m4a";
        File outFile = new File(recordingDir(), name);
        try {
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            if (quality == 1) {
                recorder.setAudioSamplingRate(44100);
                recorder.setAudioChannels(2);
                recorder.setAudioEncodingBitRate(128000);
            } else {
                recorder.setAudioSamplingRate(16000);
                recorder.setAudioChannels(1);
                recorder.setAudioEncodingBitRate(32000);
            }
            recorder.setOutputFile(outFile.getAbsolutePath());
            recorder.setMaxDuration((int) segmentMs);
            recorder.setOnInfoListener((mr, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    stopSegment();
                    if (recording && !paused) startSegment();
                }
            });
            recorder.prepare();
            recorder.start();
            return true;
        } catch (Exception e) {
            if (outFile.exists()) outFile.delete();
            stopSegment();
            sendBroadcast(new Intent(Events.ACTION_RECORD_ERROR)
                    .putExtra(Events.EXTRA_MSG, "录音失败：请确认麦克风权限已授予"));
            return false;
        }
    }

    private void stopSegment() {
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
    }

    // ---- 通知 ----

    private Notification buildNotification() {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .setContentIntent(pi);
        applyNotifText(b);
        return b.build();
    }

    private void applyNotifText(Notification.Builder b) {
        if (paused) {
            b.setContentTitle("录音已暂停")
                    .setContentText("已录 " + fmtDuration(Prefs.accumSeconds(this)));
        } else {
            b.setContentTitle("全天录音中")
                    .setContentText("已录 " + fmtDuration(Prefs.accumSeconds(this)) + " · 充电自动上传");
        }
    }

    private void refreshNotification() {
        if (!recording) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification());
    }

    public static String fmtDuration(int seconds) {
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    // ---- 计时 ----

    private void startTick() {
        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, 1000);
    }

    private void stopTick() {
        handler.removeCallbacks(tickRunnable);
    }

    // ---- 保护策略：低电量 + 存储 ----

    private void registerBattery() {
        batteryReceiver = new BroadcastReceiver() {
            boolean first = true;

            @Override
            public void onReceive(Context c, Intent i) {
                // 跳过粘性广播首帧，避免"刚点开始就被立即停止"
                if (first) { first = false; return; }
                int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level < 0 || scale <= 0) return;
                int pct = level * 100 / scale;
                if (pct <= 15 && recording && !paused && Prefs.lowBatteryStop(RecordService.this)) {
                    sendBroadcast(new Intent(Events.ACTION_NOTIFY)
                            .putExtra(Events.EXTRA_MSG, "电量仅剩 " + pct + "%，已自动停止录音"));
                    doStop();
                }
            }
        };
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private void checkStorage() {
        try {
            File dir = recordingDir();
            StatFs stat = new StatFs(dir.getAbsolutePath());
            long total = stat.getTotalBytes();
            long avail = stat.getAvailableBytes();
            if (total <= 0) return;
            int usedPct = (int) ((total - avail) * 100 / total);
            int threshold = Prefs.storageThreshold(this);
            if (usedPct >= threshold) {
                long now = System.currentTimeMillis();
                if (now - lastStorageWarn < 10 * 60 * 1000L) return; // 10 分钟内不重复提醒
                lastStorageWarn = now;
                if (Prefs.storageAutoClean(this)) {
                    int freed = cleanOldest(threshold);
                    sendBroadcast(new Intent(Events.ACTION_NOTIFY)
                            .putExtra(Events.EXTRA_MSG, "存储已用 " + usedPct + "%，自动清理了 " + freed + " 段最旧录音"));
                } else {
                    sendBroadcast(new Intent(Events.ACTION_NOTIFY)
                            .putExtra(Events.EXTRA_MSG, "存储已用 " + usedPct + "%，请及时充电上传"));
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 删除最旧录音直到占用降到阈值以下，返回删除段数 */
    private int cleanOldest(int threshold) {
        int freed = 0;
        File[] files = RecorderFiles.pending(this);
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (!f.delete()) continue;
            freed++;
            try {
                StatFs stat = new StatFs(recordingDir().getAbsolutePath());
                long total = stat.getTotalBytes();
                long avail = stat.getAvailableBytes();
                if (total <= 0 || (total - avail) * 100 / total < threshold) break;
            } catch (Exception ignored) {
                break;
            }
        }
        return freed;
    }

    // ---- 唤醒锁 ----

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "recorder:wakelock");
                wakeLock.setReferenceCounted(false);
            }
        }
        if (wakeLock != null && !wakeLock.isHeld()) wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override
    public void onDestroy() {
        stopSegment();
        stopTick();
        releaseWakeLock();
        if (batteryReceiver != null) {
            try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
            batteryReceiver = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
