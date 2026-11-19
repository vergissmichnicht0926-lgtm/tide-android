package com.whaleeye.tide;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

/** 设置主页。 */
public final class SettingsActivity extends Activity {
    private Session session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.apply(this);
        session = new Session(this);
        if (!session.hasLogin()) {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }
        setContentView(R.layout.activity_settings);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.row_account_security).setOnClickListener(v ->
                startActivity(new Intent(this, AccountSecurityActivity.class)));
        findViewById(R.id.row_settings_devices).setOnClickListener(v ->
                startActivity(new Intent(this, DeviceActivity.class)));
        findViewById(R.id.row_about).setOnClickListener(v ->
                startActivity(new Intent(this, AboutActivity.class)));
        findViewById(R.id.row_settings_logout).setOnClickListener(v -> confirmLogout());
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_logout)
                .setMessage(R.string.logout_confirm)
                .setPositiveButton(R.string.action_logout_confirm, (d, w) -> {
                    TideService.stop(this);
                    LockManager.lockAndWipe();
                    new Session(this).clearLogin();
                    startActivity(new Intent(this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    finish();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
