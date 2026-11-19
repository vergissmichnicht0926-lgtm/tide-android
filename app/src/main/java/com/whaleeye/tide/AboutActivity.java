package com.whaleeye.tide;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

/** 关于：版本号与服务器地址只读。 */
public final class AboutActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.apply(this);
        setContentView(R.layout.activity_about);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.text_about_version)).setText(
                BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        ((TextView) findViewById(R.id.text_about_server)).setText(
                new Session(this).baseUrl());
    }
}
