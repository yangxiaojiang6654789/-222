package com.watch.recorder;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/** 本地录音文件列表：查看与删除 */
public class FilesActivity extends Activity {

    private LinearLayout fileList;
    private TextView fileSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_files);

        fileList = findViewById(R.id.fileList);
        fileSummary = findViewById(R.id.fileSummary);
        Button btnClearAll = findViewById(R.id.btnClearAll);
        Button btnBack = findViewById(R.id.btnBack);

        btnBack.setOnClickListener(v -> finish());
        btnClearAll.setOnClickListener(v -> {
            File[] files = RecorderFiles.pending(this);
            if (files.length == 0) {
                Toast.makeText(this, "没有可删除的录音", Toast.LENGTH_SHORT).show();
                return;
            }
            int n = 0;
            for (File f : files) if (f.delete()) n++;
            Toast.makeText(this, "已删除 " + n + " 段", Toast.LENGTH_SHORT).show();
            rebuild();
        });

        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    private void rebuild() {
        fileList.removeAllViews();
        File[] files = RecorderFiles.pending(this);
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        fileSummary.setText("共 " + files.length + " 段 · "
                + RecorderFiles.fmtSize(RecorderFiles.totalBytes(this)));
        if (files.length == 0) {
            TextView empty = new TextView(this);
            empty.setText("暂无录音文件");
            empty.setPadding(0, 24, 0, 24);
            fileList.addView(empty);
            return;
        }

        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        for (File f : files) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 8, 0, 8);

            TextView tv = new TextView(this);
            tv.setText(f.getName() + "\n" + RecorderFiles.fmtSize(f.length())
                    + " · " + sdf.format(new Date(f.lastModified())));
            tv.setTextSize(13);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(lp);
            row.addView(tv);

            Button del = new Button(this);
            del.setText("删除");
            del.setTextSize(13);
            del.setOnClickListener(v -> {
                if (f.delete()) {
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    rebuild();
                }
            });
            row.addView(del);

            fileList.addView(row);
        }
    }
}
