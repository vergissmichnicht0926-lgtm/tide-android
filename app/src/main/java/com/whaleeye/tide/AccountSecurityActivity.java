package com.whaleeye.tide;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;
import android.widget.Toast;

/** 账号与安全：应用锁可关闭，指纹/人脸可开关。 */
public final class AccountSecurityActivity extends Activity {
    private Switch appLock;
    private Switch fingerprint;
    private Switch allowScreenshots;
    private Switch notificationContent;
    private View fingerprintRow;
    private boolean updating;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.apply(this);
        setContentView(R.layout.activity_account_security);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        appLock = findViewById(R.id.switch_app_lock);
        fingerprint = findViewById(R.id.switch_fingerprint);
        allowScreenshots = findViewById(R.id.switch_allow_screenshots);
        notificationContent = findViewById(R.id.switch_notification_content);
        fingerprintRow = findViewById(R.id.row_fingerprint);

        appLock.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            if (checked) {
                AutoUnlock.enable(this);
                Toast.makeText(this, R.string.security_app_lock_on,
                        Toast.LENGTH_SHORT).show();
                render();
            } else {
                setAppLockChecked(true);
                new AlertDialog.Builder(this)
                        .setTitle(R.string.security_app_lock)
                        .setMessage(R.string.security_app_lock_confirm)
                        .setPositiveButton(R.string.action_disable, (d, w) -> {
                            try {
                                AutoUnlock.disable(this);
                                Toast.makeText(this, R.string.security_app_lock_off,
                                        Toast.LENGTH_SHORT).show();
                                render();
                            } catch (Exception e) {
                                Toast.makeText(this, "关闭失败：" + message(e),
                                        Toast.LENGTH_LONG).show();
                            }
                        })
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            }
        });

        if (BioGate.isSupported(this)) {
            fingerprint.setOnCheckedChangeListener((button, checked) -> {
                if (updating || !LockManager.isLockEnabled(this)) return;
                if (checked) enableBiometric();
                else {
                    LockManager.disableBio(this);
                    Toast.makeText(this, R.string.security_fingerprint_off,
                            Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            fingerprintRow.setVisibility(View.GONE);
        }

        allowScreenshots.setOnCheckedChangeListener((button, checked) -> {
            if (updating || BuildConfig.ALLOW_SCREENSHOTS) return;
            if (!checked) {
                SecureWindow.setAllowed(this, false);
                Toast.makeText(this, R.string.security_allow_screenshots_off,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            setAllowScreenshotsChecked(false);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.security_allow_screenshots)
                    .setMessage(R.string.security_allow_screenshots_confirm)
                    .setPositiveButton(R.string.action_enable, (d, w) -> {
                        SecureWindow.setAllowed(this, true);
                        setAllowScreenshotsChecked(true);
                        Toast.makeText(this, R.string.security_allow_screenshots_on,
                                Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
        });
        notificationContent.setOnCheckedChangeListener((button, checked) -> {
            if (updating) return;
            NotificationPrefs.setShowContentOnLockscreen(this, checked);
            TideService.refreshNotification();
            TideService.refreshMessageNotifications();
        });
        render();
    }

    private void render() {
        boolean enabled = LockManager.isLockEnabled(this);
        setAppLockChecked(enabled);
        updating = true;
        allowScreenshots.setEnabled(!BuildConfig.ALLOW_SCREENSHOTS);
        allowScreenshots.setChecked(BuildConfig.ALLOW_SCREENSHOTS
                || SecureWindow.isAllowed(this));
        notificationContent.setChecked(NotificationPrefs.showContentOnLockscreen(this));
        updating = false;
        if (!BioGate.isSupported(this)) return;
        updating = true;
        fingerprint.setEnabled(enabled);
        fingerprintRow.setAlpha(enabled ? 1f : 0.45f);
        fingerprint.setChecked(enabled && BioGate.isEnabled(this));
        updating = false;
    }

    private void setAppLockChecked(boolean checked) {
        updating = true;
        appLock.setChecked(checked);
        updating = false;
    }

    private void setAllowScreenshotsChecked(boolean checked) {
        updating = true;
        allowScreenshots.setChecked(checked);
        updating = false;
    }

    private void enableBiometric() {
        BioGate.bind(this, new BioGate.Callback() {
            @Override
            public void onSuccess() {
                LockManager.setBioEnabled(AccountSecurityActivity.this, true);
                Toast.makeText(AccountSecurityActivity.this,
                        R.string.security_fingerprint_on, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onCancelled() {
                render();
            }

            @Override
            public void onFailure(String message) {
                render();
                Toast.makeText(AccountSecurityActivity.this,
                        getString(R.string.security_bio_failed, message),
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onPermanentFailure(String message) {
                render();
                Toast.makeText(AccountSecurityActivity.this,
                        getString(R.string.security_bio_failed, message),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
