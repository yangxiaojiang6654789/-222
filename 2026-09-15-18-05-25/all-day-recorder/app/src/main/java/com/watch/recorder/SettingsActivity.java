package com.watch.recorder;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Toast;

public class SettingsActivity extends Activity {

    private EditText etUrl, etUser, etPass, etFolder;
    private CheckBox cbAutoStart, cbAutoUpload, cbWifiOnly;
    private RadioGroup rgQuality, rgSegment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        etUrl = findViewById(R.id.etUrl);
        etUser = findViewById(R.id.etUser);
        etPass = findViewById(R.id.etPass);
        etFolder = findViewById(R.id.etFolder);
        cbAutoStart = findViewById(R.id.cbAutoStart);
        cbAutoUpload = findViewById(R.id.cbAutoUpload);
        cbWifiOnly = findViewById(R.id.cbWifiOnly);
        rgQuality = findViewById(R.id.rgQuality);
        rgSegment = findViewById(R.id.rgSegment);
        Button btnBattery = findViewById(R.id.btnBattery);
        Button btnSave = findViewById(R.id.btnSave);

        // 回填当前值
        etUrl.setText(Prefs.davUrl(this));
        etUser.setText(Prefs.davUser(this));
        etPass.setText(Prefs.davPass(this));
        etFolder.setText(Prefs.davFolder(this));
        cbAutoStart.setChecked(Prefs.autoStart(this));
        cbAutoUpload.setChecked(Prefs.autoUpload(this));
        cbWifiOnly.setChecked(Prefs.wifiOnly(this));

        int q = Prefs.quality(this);
        rgQuality.check(q == 1 ? R.id.rbQualityHigh : R.id.rbQualityLow);
        int seg = Prefs.segmentMinutes(this);
        rgSegment.check(segToId(seg));

        btnBattery.setOnClickListener(v -> openBatterySettings());
        btnSave.setOnClickListener(v -> save());
    }

    private int segToId(int seg) {
        switch (seg) {
            case 5: return R.id.rbSeg5;
            case 15: return R.id.rbSeg15;
            case 30: return R.id.rbSeg30;
            default: return R.id.rbSeg10;
        }
    }

    private int idToSeg(int id) {
        if (id == R.id.rbSeg5) return 5;
        if (id == R.id.rbSeg15) return 15;
        if (id == R.id.rbSeg30) return 30;
        return 10;
    }

    private void openBatterySettings() {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            } catch (Exception ignored) {
            }
        }
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception ignored) {
            Toast.makeText(this, "请手动到系统设置关闭本应用的省电限制", Toast.LENGTH_LONG).show();
        }
    }

    private void save() {
        String url = etUrl.getText().toString().trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "地址不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        Prefs.putString(this, Prefs.KEY_DAV_URL, url);
        Prefs.putString(this, Prefs.KEY_DAV_USER, etUser.getText().toString().trim());
        Prefs.putString(this, Prefs.KEY_DAV_PASS, etPass.getText().toString().trim());
        Prefs.putString(this, Prefs.KEY_DAV_FOLDER, etFolder.getText().toString().trim());

        Prefs.putBool(this, Prefs.KEY_AUTO_START, cbAutoStart.isChecked());
        Prefs.putBool(this, Prefs.KEY_AUTO_UPLOAD, cbAutoUpload.isChecked());
        Prefs.putBool(this, Prefs.KEY_WIFI_ONLY, cbWifiOnly.isChecked());
        Prefs.putInt(this, Prefs.KEY_QUALITY,
                rgQuality.getCheckedRadioButtonId() == R.id.rbQualityHigh ? 1 : 0);
        Prefs.putInt(this, Prefs.KEY_SEGMENT_MIN, idToSeg(rgSegment.getCheckedRadioButtonId()));

        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }
}
