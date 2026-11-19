package com.whaleeye.tide;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** 单个用户：公开资料、用量、重置设备信任。 */
public final class AdminUserDetailActivity extends Activity {
    public static final String EXTRA_USER_JSON = "user_json";
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;
    private TideClient client;
    private JSONObject user;
    private TextView resetRow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.apply(this);
        session = new Session(this);
        if (!session.hasLogin() || !session.isAdmin()) {
            startActivity(new Intent(this, MessagesActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }
        client = new TideClient(session);
        try {
            user = new JSONObject(getIntent().getStringExtra(EXTRA_USER_JSON));
        } catch (Exception e) {
            finish();
            return;
        }
        setContentView(R.layout.activity_admin_user_detail);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        resetRow = findViewById(R.id.row_reset_devices);
        bind();
    }

    private void bind() {
        String name = user.optString("displayName", user.optString("username", "?"));
        String username = user.optString("username", "");
        TextView avatar = findViewById(R.id.user_avatar);
        avatar.setText(TextUtils.isEmpty(name) ? "?" : name.substring(0, 1));
        ((TextView) findViewById(R.id.user_name)).setText(name);
        ((TextView) findViewById(R.id.user_username)).setText("@" + username);
        ((TextView) findViewById(R.id.user_role)).setText(
                "admin".equals(user.optString("role", "guest"))
                        ? R.string.admin_role_admin : R.string.admin_role_guest);
        ((TextView) findViewById(R.id.user_stats)).setText(getString(
                R.string.admin_user_stats,
                user.optInt("todayMessages", 0),
                user.optInt("conversationCount", 0),
                user.optInt("messageCount", 0),
                when(user.optString("lastActiveAt", ""))));

        boolean self = username.equals(session.username());
        TextView note = findViewById(R.id.text_reset_note);
        note.setVisibility(self ? android.view.View.VISIBLE : android.view.View.GONE);
        if (self) {
            resetRow.setAlpha(0.45f);
            resetRow.setOnClickListener(v -> Toast.makeText(this,
                    R.string.admin_reset_self_note, Toast.LENGTH_LONG).show());
        } else {
            resetRow.setOnClickListener(v -> confirmReset());
        }
    }

    private void confirmReset() {
        String name = user.optString("displayName", user.optString("username", ""));
        new AlertDialog.Builder(this)
                .setTitle(R.string.admin_reset_device_trust)
                .setMessage("会撤销「" + name + "」全部设备和登录，并生成一次性重绑码。")
                .setPositiveButton(R.string.admin_reset_device_trust,
                        (d, w) -> resetDevices())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void resetDevices() {
        String userId = user.optString("userId", "");
        new Thread(() -> {
            try {
                JSONObject response = client.resetDeviceTrust(userId);
                String code = response.optString("resetCode", "");
                String expiresAt = response.optString("expiresAt", "");
                main.post(() -> showResult(code, expiresAt));
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this,
                        "重置失败：" + message(e), Toast.LENGTH_LONG).show());
            }
        }, "tide-admin-reset").start();
    }

    private void showResult(String code, String expiresAt) {
        if (TextUtils.isEmpty(code)) {
            Toast.makeText(this, R.string.admin_reset_code_missing,
                    Toast.LENGTH_LONG).show();
            return;
        }
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_reset_result);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(dp(300),
                    getResources().getDisplayMetrics().widthPixels - dp(40));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        ((TextView) dialog.findViewById(R.id.reset_message))
                .setText(R.string.admin_reset_message);
        ((TextView) dialog.findViewById(R.id.reset_code)).setText(code);
        ((TextView) dialog.findViewById(R.id.reset_expiry))
                .setText(getString(R.string.admin_reset_expiry, when(expiresAt)));
        dialog.findViewById(R.id.btn_copy).setOnClickListener(v -> {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("重绑码", code));
                Toast.makeText(this, R.string.admin_copy_done, Toast.LENGTH_SHORT).show();
            }
        });
        dialog.findViewById(R.id.btn_close).setOnClickListener(v -> dialog.dismiss());
    }

    private static String when(String iso) {
        if (iso == null || iso.length() < 19) return "未知";
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = in.parse(iso.substring(0, 19));
            if (date == null) return "未知";
            SimpleDateFormat out = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
            out.setTimeZone(TimeZone.getDefault());
            return out.format(date);
        } catch (Exception e) {
            return "未知";
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
