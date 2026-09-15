package com.watch.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/** 上传服务：遍历录音目录，逐个上传到 WebDAV，成功后删除本地文件，带进度通知与结果广播 */
public class UploadService extends Service {

    private static final String TAG = "UploadService";
    private static final String CHANNEL_ID = "upload_channel";
    private static final int NOTIF_ID = 1002;

    private Thread thread;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification("准备上传…", 0, 0, false));
        if (thread == null || !thread.isAlive()) {
            thread = new Thread(this::doUpload);
            thread.start();
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String text, int progress, int max, boolean indeterminate) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "上传中", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("上传录音")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .setProgress(max, progress, indeterminate);
        return b.build();
    }

    private void updateNotif(String text, int progress, int max) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text, progress, max, false));
    }

    private boolean isWifi(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected()
                && (ni.getType() == ConnectivityManager.TYPE_WIFI
                || ni.getType() == ConnectivityManager.TYPE_ETHERNET);
    }

    private void doUpload() {
        Context c = this;
        String user = Prefs.davUser(c);
        String pass = Prefs.davPass(c);
        if (user == null || user.isEmpty() || pass == null || pass.isEmpty()) {
            finish("未配置 WebDAV，请先到设置里填写账号密码");
            return;
        }
        if (Prefs.wifiOnly(c) && !isWifi(c)) {
            finish("当前非 WiFi，已按设置跳过上传");
            return;
        }

        WebDavUploader uploader = new WebDavUploader(
                Prefs.davUrl(c), user, pass, Prefs.davFolder(c));
        uploader.ensureFolder();

        File[] files = RecorderFiles.pending(c);
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));

        if (files.length == 0) {
            finish("没有待上传的录音");
            return;
        }

        int total = files.length;
        int ok = 0, fail = 0, done = 0;
        String lastError = null;
        for (File f : files) {
            updateNotif("正在上传 " + (done + 1) + "/" + total + "："
                    + f.getName(), done, total);
            try {
                if (uploader.upload(f)) {
                    if (f.delete()) ok++;
                }
            } catch (Exception e) {
                fail++;
                lastError = e.getMessage();
                Log.w(TAG, "upload error " + f.getName() + ": " + lastError);
            }
            done++;
        }
        updateNotif("上传完成", total, total);
        String msg = "上传完成：成功 " + ok + " 段"
                + (fail > 0 ? "，失败 " + fail + " 段" : "");
        if (fail > 0) {
            msg += "（" + friendlyError(lastError) + "）";
        }
        finish(msg);
    }

    private String friendlyError(String e) {
        if (e == null) return "未知错误";
        if (e.contains("401") || e.contains("403")) return "账号或密码错误";
        if (e.contains("404")) return "目录不存在";
        if (e.contains("UnknownHost") || e.contains("ConnectException")
                || e.contains("timed out") || e.contains("SocketTimeout")
                || e.contains("Network")) return "网络不可用";
        return "网络或服务器异常";
    }

    private void finish(String msg) {
        Prefs.putString(this, Prefs.KEY_LAST_UPLOAD, msg);
        sendBroadcast(new Intent(Events.ACTION_UPLOAD_DONE)
                .putExtra(Events.EXTRA_UPLOAD_MSG, msg));
        stopForeground(true);
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
