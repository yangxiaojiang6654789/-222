package com.watch.recorder;

/** 广播事件常量 */
public final class Events {
    private Events() {
    }

    /** 上传完成（携带结果文案） */
    public static final String ACTION_UPLOAD_DONE = "com.watch.recorder.UPLOAD_DONE";
    public static final String EXTRA_UPLOAD_MSG = "upload_msg";

    /** 录音出错（携带错误文案） */
    public static final String ACTION_RECORD_ERROR = "com.watch.recorder.RECORD_ERROR";
    public static final String EXTRA_MSG = "msg";

    /** 通用提示（低电量停止、存储告警等） */
    public static final String ACTION_NOTIFY = "com.watch.recorder.NOTIFY";
}
