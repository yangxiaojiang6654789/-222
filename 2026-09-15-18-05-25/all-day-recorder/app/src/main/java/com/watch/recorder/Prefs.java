package com.watch.recorder;

import android.content.Context;
import android.content.SharedPreferences;

/** 全局配置与状态存取（单一数据源） */
public class Prefs {

    private static final String NAME = "recorder_prefs";

    // WebDAV 配置
    public static final String KEY_DAV_URL = "dav_url";
    public static final String KEY_DAV_USER = "dav_user";
    public static final String KEY_DAV_PASS = "dav_pass";
    public static final String KEY_DAV_FOLDER = "dav_folder";

    // 录音运行状态
    public static final String KEY_RECORDING = "recording";         // 服务是否在录
    public static final String KEY_PAUSED = "paused";               // 是否暂停
    public static final String KEY_ACCUM_SECONDS = "accum_seconds"; // 本次会话累计秒数
    public static final String KEY_TODAY_SEGMENTS = "today_segments"; // 今日已录段数
    public static final String KEY_TODAY_DATE = "today_date";       // 段数统计所属日期

    // 行为配置
    public static final String KEY_AUTO_START = "auto_start";       // 开机自启
    public static final String KEY_AUTO_UPLOAD = "auto_upload";     // 充电自动上传
    public static final String KEY_WIFI_ONLY = "wifi_only";         // 仅 WiFi 上传
    public static final String KEY_QUALITY = "quality";             // 0=省电 1=高音质
    public static final String KEY_SEGMENT_MIN = "segment_min";     // 分段时长（分钟）

    // 上传结果
    public static final String KEY_LAST_UPLOAD = "last_upload";     // 上次上传结果描述

    // 保护策略
    public static final String KEY_LOW_BATTERY_STOP = "low_battery_stop";   // 低电量自动停止
    public static final String KEY_STORAGE_AUTO_CLEAN = "storage_auto_clean"; // 存储满自动清理最旧
    public static final String KEY_STORAGE_THRESHOLD = "storage_threshold";   // 存储占用告警阈值(%)

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String getString(Context c, String key, String def) {
        return sp(c).getString(key, def);
    }

    public static void putString(Context c, String key, String val) {
        sp(c).edit().putString(key, val).apply();
    }

    public static boolean getBool(Context c, String key, boolean def) {
        return sp(c).getBoolean(key, def);
    }

    public static void putBool(Context c, String key, boolean val) {
        sp(c).edit().putBoolean(key, val).apply();
    }

    public static int getInt(Context c, String key, int def) {
        return sp(c).getInt(key, def);
    }

    public static void putInt(Context c, String key, int val) {
        sp(c).edit().putInt(key, val).apply();
    }

    // ---- WebDAV ----

    public static String davUrl(Context c) {
        String u = getString(c, KEY_DAV_URL, "https://dav.mypikpak.com");
        if (u == null || u.trim().isEmpty()) u = "https://dav.mypikpak.com";
        if (!u.endsWith("/")) u += "/";
        return u;
    }

    public static String davUser(Context c) {
        return getString(c, KEY_DAV_USER, "");
    }

    public static String davPass(Context c) {
        return getString(c, KEY_DAV_PASS, "");
    }

    public static String davFolder(Context c) {
        String f = getString(c, KEY_DAV_FOLDER, "watch-recordings");
        if (f == null || f.trim().isEmpty()) f = "watch-recordings";
        f = f.trim();
        if (f.startsWith("/")) f = f.substring(1);
        if (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        return f;
    }

    // ---- 录音状态 ----

    public static boolean isRecording(Context c) {
        return getBool(c, KEY_RECORDING, false);
    }

    public static boolean isPaused(Context c) {
        return getBool(c, KEY_PAUSED, false);
    }

    public static int accumSeconds(Context c) {
        return getInt(c, KEY_ACCUM_SECONDS, 0);
    }

    // ---- 行为配置 ----

    public static boolean autoStart(Context c) {
        return getBool(c, KEY_AUTO_START, true);
    }

    public static boolean autoUpload(Context c) {
        return getBool(c, KEY_AUTO_UPLOAD, true);
    }

    public static boolean wifiOnly(Context c) {
        return getBool(c, KEY_WIFI_ONLY, false);
    }

    /** 音质档位：0 省电，1 高音质 */
    public static int quality(Context c) {
        return getInt(c, KEY_QUALITY, 0);
    }

    /** 分段时长（分钟） */
    public static int segmentMinutes(Context c) {
        return getInt(c, KEY_SEGMENT_MIN, 10);
    }

    /** 低电量自动停止（默认开） */
    public static boolean lowBatteryStop(Context c) {
        return getBool(c, KEY_LOW_BATTERY_STOP, true);
    }

    /** 存储满自动清理最旧（默认关，避免误删） */
    public static boolean storageAutoClean(Context c) {
        return getBool(c, KEY_STORAGE_AUTO_CLEAN, false);
    }

    /** 存储占用告警阈值（%，默认 80） */
    public static int storageThreshold(Context c) {
        int t = getInt(c, KEY_STORAGE_THRESHOLD, 80);
        return t > 0 && t <= 100 ? t : 80;
    }
}
