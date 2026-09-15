package com.watch.recorder;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** WebDAV 上传器：HTTP PUT + Basic 认证 */
public class WebDavUploader {

    private final String baseUrl;
    private final String user;
    private final String pass;
    private final String folder;

    public WebDavUploader(String baseUrl, String user, String pass, String folder) {
        this.baseUrl = baseUrl;
        this.user = user;
        this.pass = pass;
        this.folder = folder;
    }

    private HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        String auth = "Basic " + Base64.encodeToString(
                (user + ":" + pass).getBytes("UTF-8"), Base64.NO_WRAP);
        conn.setRequestProperty("Authorization", auth);
        return conn;
    }

    /** 尝试创建目录（MKCOL），已存在则忽略 */
    public void ensureFolder() {
        try {
            HttpURLConnection conn = open(baseUrl + folder, "MKCOL");
            conn.getResponseCode(); // 201 成功 / 405 已存在，均可忽略
            conn.disconnect();
        } catch (Exception ignored) {
        }
    }

    public boolean upload(File file) throws Exception {
        HttpURLConnection conn = open(baseUrl + folder + "/" + file.getName(), "PUT");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.setFixedLengthStreamingMode(file.length());
        try (FileInputStream fin = new FileInputStream(file);
             BufferedInputStream bin = new BufferedInputStream(fin, 8192);
             OutputStream out = conn.getOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = bin.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
        int code = conn.getResponseCode();
        conn.disconnect();
        if (code >= 200 && code < 300) return true;
        throw new java.io.IOException("HTTP " + code);
    }
}
