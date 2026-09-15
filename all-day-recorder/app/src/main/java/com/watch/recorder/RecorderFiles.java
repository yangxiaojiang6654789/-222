package com.watch.recorder;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 录音文件目录与遍历 */
public class RecorderFiles {

    public static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "recordings");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 所有待上传的录音文件 */
    public static File[] pending(Context c) {
        File d = dir(c);
        File[] files = d.listFiles((File f) -> f.isFile() && f.getName().endsWith(".m4a"));
        return files == null ? new File[0] : files;
    }

    public static long totalBytes(Context c) {
        long s = 0;
        for (File f : pending(c)) s += f.length();
        return s;
    }

    /** 今日已录段数（按文件名日期前缀统计） */
    public static int todayCount(Context c) {
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        int n = 0;
        for (File f : pending(c)) {
            if (f.getName().startsWith(today)) n++;
        }
        return n;
    }

    public static String fmtSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}
