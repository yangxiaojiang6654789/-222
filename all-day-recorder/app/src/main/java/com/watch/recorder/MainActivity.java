package com.watch.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class MainActivity extends Activity {

    private static final int REQ_PERM = 1;

    private TextView statusText;
    private Button btnToggle;
    private Button btnStop;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refresh();
            uiHandler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String a = intent.getAction();
            if (Events.ACTION_UPLOAD_DONE.equals(a)) {
                Toast.makeText(c, intent.getStringExtra(Events.EXTRA_UPLOAD_MSG), Toast.LENGTH_LONG).show();
                refresh();
            } else if (Events.ACTION_RECORD_ERROR.equals(a)) {
                Toast.makeText(c, intent.getStringExtra(Events.EXTRA_MSG), Toast.LENGTH_LONG).show();
                refresh();
            } else if (Events.ACTION_NOTIFY.equals(a)) {
                Toast.makeText(c, intent.getStringExtra(Events.EXTRA_MSG), Toast.LENGTH_LONG).show();
                refresh();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        btnToggle = findViewById(R.id.btnToggle);
        btnStop = findViewById(R.id.btnStop);
        Button btnUpload = findViewById(R.id.btnUpload);
        Button btnFiles = findViewById(R.id.btnFiles);
        Button btnSettings = findViewById(R.id.btnSettings);

        btnToggle.setOnClickListener(v -> onToggle());
        btnStop.setOnClickListener(v -> onStop());
        btnUpload.setOnClickListener(v -> onUpload());
        btnFiles.setOnClickListener(v ->
                startActivity(new Intent(this, FilesActivity.class)));
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        IntentFilter filter = new IntentFilter();
        filter.addAction(Events.ACTION_UPLOAD_DONE);
        filter.addAction(Events.ACTION_RECORD_ERROR);
        filter.addAction(Events.ACTION_NOTIFY);
        registerReceiver(receiver, filter);

        WatchdogReceiver.schedule(this);

        refresh();
    }

    private void onToggle() {
        boolean recording = Prefs.isRecording(this);
        boolean paused = Prefs.isPaused(this);
        if (!recording) {
            if (!hasMicPermission()) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM);
                return;
            }
            startRecord(RecordService.ACTION_START);
        } else if (paused) {
            startRecord(RecordService.ACTION_RESUME);
        } else {
            startRecord(RecordService.ACTION_PAUSE);
        }
        refresh();
    }

    private void onStop() {
        startRecord(RecordService.ACTION_STOP);
        refresh();
    }

    private void onUpload() {
        if (TextUtils.isEmpty(Prefs.davUser(this)) || TextUtils.isEmpty(Prefs.davPass(this))) {
            Toast.makeText(this, "请先在设置里填 WebDAV 账号密码", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        Intent s = new Intent(this, UploadService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s);
        else startService(s);
    }

    private void startRecord(String action) {
        Intent s = new Intent(this, RecordService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s);
        else startService(s);
    }

    private boolean hasMicPermission() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        uiHandler.removeCallbacks(refreshRunnable);
        uiHandler.postDelayed(refreshRunnable, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(refreshRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(refreshRunnable);
        unregisterReceiver(receiver);
    }

    private void refresh() {
        boolean recording = Prefs.isRecording(this);
        boolean paused = Prefs.isPaused(this);
        int sec = Prefs.accumSeconds(this);
        File[] pending = RecorderFiles.pending(this);
        String lastUpload = Prefs.getString(this, Prefs.KEY_LAST_UPLOAD, "");

        if (recording && paused) {
            btnToggle.setText("继续录音");
            btnStop.setEnabled(true);
        } else if (recording) {
            btnToggle.setText("暂停");
            btnStop.setEnabled(true);
        } else {
            btnToggle.setText("开始录音");
            btnStop.setEnabled(false);
        }

        StringBuilder sb = new StringBuilder();
        if (recording && paused) {
            sb.append("❚❚ 已暂停");
        } else if (recording) {
            sb.append("● 正在录音");
        } else {
            sb.append("○ 已停止");
        }
        sb.append(" · ").append(RecordService.fmtDuration(sec)).append("\n");
        sb.append("今日 ").append(RecorderFiles.todayCount(this)).append(" 段")
                .append(" · 待上传 ").append(pending.length).append(" 段\n");
        sb.append("占用 ").append(RecorderFiles.fmtSize(RecorderFiles.totalBytes(this)));
        if (recording && !ignoringBatteryOpt()) {
            sb.append("\n⚠ 未加入电池白名单，息屏可能停止录音");
        }
        if (!lastUpload.isEmpty()) {
            sb.append("\n").append(lastUpload);
        }
        statusText.setText(sb.toString());
    }

    private boolean ignoringBatteryOpt() {
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }
        return true;
    }
}
