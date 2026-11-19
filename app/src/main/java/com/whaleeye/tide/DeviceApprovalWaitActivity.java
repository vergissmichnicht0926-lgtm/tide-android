package com.whaleeye.tide;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** 新设备登录后的等待页：显示确认码、倒计时并轮询审批结果。 */
public final class DeviceApprovalWaitActivity extends Activity {
    public static final String EXTRA_APPROVAL_ID = "approval_id";
    public static final String EXTRA_APPROVAL_TOKEN = "approval_token";
    public static final String EXTRA_VERIFICATION_CODE = "verification_code";
    public static final String EXTRA_EXPIRES_AT = "expires_at";
    public static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_JOIN_CODE = "join_code";
    public static final String EXTRA_JOIN_KIND = "join_kind";

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean polling;
    private boolean terminal;
    private long deadline;
    private Session session;
    private TideClient client;
    private String approvalId;
    private String approvalToken;
    private String username;
    private String joinCode;
    private String joinKind;
    private TextView textCode;
    private TextView textTime;
    private TextView textStatus;
    private TextView btnCancel;
    private ImageButton btnClose;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.applySensitive(this);
        setContentView(R.layout.activity_device_approval_wait);

        textCode = findViewById(R.id.text_code);
        textTime = findViewById(R.id.text_time);
        textStatus = findViewById(R.id.text_status);
        btnCancel = findViewById(R.id.btn_cancel);
        btnClose = findViewById(R.id.btn_close);
        progress = findViewById(R.id.progress_approval);

        approvalId = getIntent().getStringExtra(EXTRA_APPROVAL_ID);
        approvalToken = getIntent().getStringExtra(EXTRA_APPROVAL_TOKEN);
        username = getIntent().getStringExtra(EXTRA_USERNAME);
        joinCode = getIntent().getStringExtra(EXTRA_JOIN_CODE);
        joinKind = getIntent().getStringExtra(EXTRA_JOIN_KIND);
        if (TextUtils.isEmpty(approvalId) || TextUtils.isEmpty(approvalToken)) {
            showTerminal(R.string.approval_expired);
            return;
        }

        session = new Session(this);
        client = new TideClient(session);
        textCode.setText(formatCode(getIntent().getStringExtra(EXTRA_VERIFICATION_CODE)));
        deadline = parseExpiry(getIntent().getStringExtra(EXTRA_EXPIRES_AT));
        if (deadline <= System.currentTimeMillis()) {
            deadline = System.currentTimeMillis() + 10 * 60 * 1000L;
        }
        btnClose.setOnClickListener(v -> stopAndFinish());
        btnCancel.setOnClickListener(v -> stopAndFinish());
        tick();
        startPolling();
    }

    private void tick() {
        if (terminal) return;
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            showTerminal(R.string.approval_expired);
            return;
        }
        long seconds = (remaining + 999) / 1000;
        textTime.setText(String.format(Locale.US, "%d:%02d 后失效",
                seconds / 60, seconds % 60));
        main.postDelayed(this::tick, 1000);
    }

    private void startPolling() {
        polling = true;
        new Thread(() -> {
            for (int i = 0; i < 300 && polling; i++) {
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!polling) return;
                try {
                    JSONObject status = client.deviceApprovalStatus(approvalId, approvalToken);
                    String state = status.optString("status", "");
                    if ("approved".equals(state)) {
                        String access = firstNonNull(status, "accessToken", "access_token");
                        if (!TextUtils.isEmpty(access)) {
                            main.post(() -> finishApproved(status));
                            return;
                        }
                    }
                    if ("rejected".equals(state)) {
                        main.post(() -> showTerminal(R.string.approval_rejected));
                        return;
                    }
                    if ("expired".equals(state)) {
                        main.post(() -> showTerminal(R.string.approval_expired));
                        return;
                    }
                } catch (Exception e) {
                    main.post(() -> {
                        if (polling) textStatus.setText(R.string.approval_network);
                    });
                }
            }
        }, "tide-device-approval-wait").start();
    }

    private void finishApproved(JSONObject status) {
        polling = false;
        terminal = true;
        try {
            AuthSession.apply(session, status, username);
        } catch (Exception e) {
            showTerminalMessage("审批已通过，但没有拿到登录凭据");
            return;
        }
        Intent home = new Intent(this, MessagesActivity.class);
        if (!TextUtils.isEmpty(joinCode)) {
            home.putExtra(MainActivity.EXTRA_JOIN_CODE, joinCode);
            home.putExtra(MainActivity.EXTRA_JOIN_KIND, joinKind);
        }
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(home);
        finish();
    }

    private void showTerminal(int messageRes) {
        showTerminalMessage(getString(messageRes));
    }

    private void showTerminalMessage(String message) {
        polling = false;
        terminal = true;
        progress.setVisibility(View.GONE);
        textStatus.setText(message);
        btnCancel.setText(R.string.approval_relogin);
        btnCancel.setOnClickListener(v -> finish());
        btnClose.setOnClickListener(v -> finish());
    }

    private void stopAndFinish() {
        polling = false;
        finish();
    }

    private static String formatCode(String code) {
        if (TextUtils.isEmpty(code)) return "";
        if (code.length() == 6) return code.substring(0, 3) + " " + code.substring(3);
        return code;
    }

    private static long parseExpiry(String iso) {
        if (TextUtils.isEmpty(iso)) return 0;
        try {
            String value = iso.length() >= 19 ? iso.substring(0, 19) : iso;
            SimpleDateFormat format = new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = format.parse(value);
            return date == null ? 0 : date.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String firstNonNull(JSONObject object, String first, String second) {
        String value = object.optString(first, null);
        if (value == null || value.isEmpty() || "null".equals(value)) {
            value = object.optString(second, null);
        }
        if (value == null || value.isEmpty() || "null".equals(value)) return null;
        return value;
    }

    @Override
    public void onBackPressed() {
        stopAndFinish();
    }

    @Override
    protected void onDestroy() {
        polling = false;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
