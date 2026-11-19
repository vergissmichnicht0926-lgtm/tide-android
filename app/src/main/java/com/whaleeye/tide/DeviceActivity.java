package com.whaleeye.tide;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** 登录设备：待批准新设备 + 已授权设备。 */
public final class DeviceActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<ApprovalView> approvalViews = new ArrayList<>();
    private final Runnable tick = this::updateApprovalTimes;

    private Session session;
    private TideClient client;
    private LinearLayout approvalSection;
    private LinearLayout listApprovals;
    private LinearLayout listDevices;
    private TextView empty;
    private TextView hint;
    private TextView error;
    private ProgressBar progress;
    private boolean loading;

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
        client = new TideClient(session);
        setContentView(R.layout.activity_devices);

        approvalSection = findViewById(R.id.approval_section);
        listApprovals = findViewById(R.id.list_approvals);
        listDevices = findViewById(R.id.list_devices);
        empty = findViewById(R.id.text_devices_empty);
        hint = findViewById(R.id.text_devices_hint);
        error = findViewById(R.id.text_devices_error);
        progress = findViewById(R.id.progress_devices);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_devices_refresh).setOnClickListener(v -> load());
        error.setOnClickListener(v -> load());
        load();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!TideApp.ensureUnlocked(this)) return;
        if (!approvalViews.isEmpty()) updateApprovalTimes();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!loading) load();
    }

    @Override
    protected void onStop() {
        main.removeCallbacks(tick);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void load() {
        if (loading) return;
        loading = true;
        progress.setVisibility(View.VISIBLE);
        error.setVisibility(View.GONE);
        main.removeCallbacks(tick);
        approvalViews.clear();
        new Thread(() -> {
            try {
                JSONObject devicesResponse = client.listDevices();
                JSONArray devices = devicesResponse.optJSONArray("devices");
                JSONArray approvals = new JSONArray();
                try {
                    JSONObject approvalsResponse = client.deviceApprovals();
                    JSONArray value = approvalsResponse.optJSONArray("approvals");
                    if (value != null) approvals = value;
                } catch (Exception ignored) {
                    // 设备列表能看就先看；审批接口失败不拖垮整页。
                }
                final JSONArray deviceItems = devices == null ? new JSONArray() : devices;
                final JSONArray approvalItems = approvals;
                main.post(() -> render(deviceItems, approvalItems));
            } catch (Exception e) {
                main.post(() -> {
                    loading = false;
                    progress.setVisibility(View.GONE);
                    showError(getString(R.string.devices_load_failed, friendly(e)));
                });
            }
        }, "tide-devices").start();
    }

    private void render(JSONArray devices, JSONArray approvals) {
        loading = false;
        progress.setVisibility(View.GONE);
        error.setVisibility(View.GONE);
        listApprovals.removeAllViews();
        listDevices.removeAllViews();
        approvalViews.clear();

        approvalSection.setVisibility(approvals.length() > 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < approvals.length(); i++) {
            JSONObject approval = approvals.optJSONObject(i);
            if (approval != null) listApprovals.addView(approvalRow(approval));
        }

        String currentId = session.deviceId();
        List<JSONObject> rows = new ArrayList<>();
        int revokedCount = 0;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject item = devices.optJSONObject(i);
            if (item == null) continue;
            if (isRevoked(item)) {
                revokedCount++;
                continue;
            }
            rows.add(item);
        }
        Collections.sort(rows, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                boolean ac = currentId != null && currentId.equals(a.optString("id", ""));
                boolean bc = currentId != null && currentId.equals(b.optString("id", ""));
                if (ac != bc) return ac ? -1 : 1;
                return b.optString("lastSeenAt", "").compareTo(a.optString("lastSeenAt", ""));
            }
        });
        for (JSONObject device : rows) listDevices.addView(deviceRow(device, currentId));

        empty.setVisibility(rows.isEmpty() && approvals.length() == 0
                ? View.VISIBLE : View.GONE);
        if (revokedCount > 0) {
            hint.setText(getString(R.string.devices_revoked_hidden, revokedCount));
            hint.setVisibility(View.VISIBLE);
        } else {
            hint.setVisibility(View.GONE);
        }
        updateApprovalTimes();
    }

    private View approvalRow(JSONObject approval) {
        String id = approval.optString("id", "");
        String name = approval.optString("name", "未命名设备");
        String ip = approval.optString("requestIp", "未知");
        String createdAt = approval.optString("createdAt", "");
        String fingerprint = approval.optString("keyFingerprint", "");
        String shortFingerprint = fingerprint.length() > 8
                ? fingerprint.substring(0, 8) : fingerprint;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(12);
        card.setLayoutParams(cardParams);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(name, 15, R.color.ink);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView status = text(getString(R.string.devices_pending), 11, R.color.badge);
        status.setBackgroundResource(R.drawable.bg_warning);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(8), dp(4), dp(8), dp(4));
        header.addView(status);
        card.addView(header);

        TextView request = text(getString(R.string.devices_request,
                when(createdAt), ip), 11, R.color.ink_3);
        request.setPadding(0, dp(8), 0, 0);
        card.addView(request);
        TextView fp = text(getString(R.string.devices_fingerprint, shortFingerprint),
                11, R.color.ink_3);
        fp.setPadding(0, dp(2), 0, 0);
        card.addView(fp);
        TextView time = text("", 11, R.color.ink_3);
        time.setPadding(0, dp(6), 0, 0);
        card.addView(time);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, 0);
        TextView reject = actionText(getString(R.string.devices_reject), false);
        TextView approve = actionText(getString(R.string.devices_approve), true);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(
                0, dp(40), 1f);
        actions.addView(reject, half);
        LinearLayout.LayoutParams approveParams = new LinearLayout.LayoutParams(
                0, dp(40), 1f);
        approveParams.leftMargin = dp(10);
        actions.addView(approve, approveParams);
        card.addView(actions);

        long expiresAt = parseIso(approval.optString("expiresAt", ""));
        if (expiresAt <= 0) expiresAt = System.currentTimeMillis() + 10 * 60 * 1000L;
        ApprovalView view = new ApprovalView(id, approval, status, time, approve, reject, expiresAt);
        approvalViews.add(view);
        approve.setOnClickListener(v -> showApprovalDialog(view));
        reject.setOnClickListener(v -> confirmReject(view));
        return card;
    }

    private View deviceRow(JSONObject device, String currentId) {
        String id = device.optString("id", "");
        String rawName = device.optString("name", "");
        final String name = TextUtils.isEmpty(rawName) ? "未命名设备" : rawName;
        boolean current = currentId != null && currentId.equals(id);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(8);
        card.setLayoutParams(cardParams);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(name, 15, R.color.ink);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        column.addView(title);
        TextView meta = text(getString(R.string.devices_last_seen,
                when(device.optString("lastSeenAt", ""))), 11, R.color.ink_3);
        meta.setPadding(0, dp(4), 0, 0);
        column.addView(meta);
        card.addView(column, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView action = text(current
                ? getString(R.string.devices_current)
                : getString(R.string.devices_revoke),
                13, current ? R.color.ink_3 : R.color.ink_2);
        action.setPadding(dp(12), dp(8), dp(4), dp(8));
        if (!current) {
            action.setOnClickListener(v -> confirmRevoke(id, name));
        }
        card.addView(action);
        return card;
    }

    private void showApprovalDialog(ApprovalView view) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_approval_code);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int width = Math.min(dp(300),
                    getResources().getDisplayMetrics().widthPixels - dp(40));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        EditText input = dialog.findViewById(R.id.input_code);
        TextView codeError = dialog.findViewById(R.id.text_code_error);
        TextView approve = dialog.findViewById(R.id.btn_approve);
        dialog.findViewById(R.id.btn_cancel).setOnClickListener(v -> dialog.dismiss());
        approve.setOnClickListener(v -> {
            String code = input.getText().toString().trim();
            if (!code.matches("\\d{6}")) {
                codeError.setText(R.string.devices_code_invalid);
                return;
            }
            codeError.setText("");
            approve.setEnabled(false);
            decide(view, true, code, dialog, codeError, approve);
        });
        input.requestFocus();
    }

    private void confirmReject(ApprovalView view) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.devices_reject)
                .setMessage(R.string.devices_reject_confirm)
                .setPositiveButton(R.string.devices_reject,
                        (d, w) -> decide(view, false, null, null, null, null))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void decide(ApprovalView view, boolean approve, String code, Dialog dialog,
                        TextView codeError, TextView approveButton) {
        new Thread(() -> {
            try {
                if (approve) client.approveDevice(view.id, code);
                else client.rejectDevice(view.id);
                main.post(() -> {
                    if (dialog != null) dialog.dismiss();
                    Toast.makeText(this,
                            approve ? "已批准新设备" : getString(R.string.devices_rejected),
                            Toast.LENGTH_SHORT).show();
                    load();
                });
            } catch (Exception e) {
                String errorCode = e instanceof TideClient.TideException
                        ? ((TideClient.TideException) e).code : "";
                if ("device-approval-rejected".equals(errorCode)) {
                    main.post(() -> {
                        if (dialog != null) dialog.dismiss();
                        Toast.makeText(this,
                                R.string.devices_approval_void,
                                Toast.LENGTH_LONG).show();
                        load();
                    });
                    return;
                }
                if ("device-approval-decided".equals(errorCode)
                        || "device-approval-not-found".equals(errorCode)) {
                    main.post(() -> {
                        if (dialog != null) dialog.dismiss();
                        load();
                    });
                    return;
                }
                main.post(() -> {
                    String message = getString(R.string.devices_approve_failed, friendly(e));
                    if (codeError != null) {
                        codeError.setText(message);
                        if (approveButton != null) approveButton.setEnabled(true);
                    } else {
                        showError(message);
                    }
                });
            }
        }, "tide-device-approval").start();
    }

    private void updateApprovalTimes() {
        if (isFinishing()) return;
        long now = System.currentTimeMillis();
        for (ApprovalView view : approvalViews) {
            long remaining = view.expiresAt - now;
            if (remaining <= 0) {
                view.status.setText(R.string.devices_expired);
                view.time.setText(R.string.devices_expired);
                view.approve.setEnabled(false);
                view.reject.setEnabled(false);
                view.approve.setAlpha(0.45f);
                view.reject.setAlpha(0.45f);
            } else {
                long seconds = (remaining + 999) / 1000;
                view.time.setText(getString(R.string.devices_expires,
                        String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)));
            }
        }
        main.removeCallbacks(tick);
        if (!approvalViews.isEmpty()) main.postDelayed(tick, 1000);
    }

    private void confirmRevoke(String id, String name) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.devices_revoke)
                .setMessage(getString(R.string.devices_revoke_confirm, name))
                .setPositiveButton(R.string.devices_revoke, (d, w) -> revoke(id))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void revoke(String id) {
        new Thread(() -> {
            try {
                client.revokeDevice(id);
                main.post(() -> {
                    Toast.makeText(this, R.string.devices_revoked, Toast.LENGTH_SHORT).show();
                    load();
                });
            } catch (Exception e) {
                main.post(() -> showError(getString(
                        R.string.devices_revoke_failed, friendly(e))));
            }
        }, "tide-device-revoke").start();
    }

    private void showError(String message) {
        error.setText(message + " · " + getString(R.string.messages_retry));
        error.setVisibility(View.VISIBLE);
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getResources().getColor(color));
        return view;
    }

    private TextView actionText(String value, boolean primary) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(15);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(getResources().getColor(primary ? R.color.paper : R.color.ink));
        view.setBackgroundResource(primary ? R.drawable.bg_primary : R.drawable.bg_secondary);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private static String friendly(Throwable error) {
        if (error instanceof TideClient.TideException) {
            TideClient.TideException te = (TideClient.TideException) error;
            return te.code + " / " + te.getMessage();
        }
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private static boolean isRevoked(JSONObject device) {
        Object value = device.opt("revokedAt");
        if (value == null || JSONObject.NULL.equals(value)) return false;
        return !"null".equals(String.valueOf(value));
    }

    private static String when(String iso) {
        long millis = parseIso(iso);
        if (millis <= 0) return "未知";
        SimpleDateFormat out = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        out.setTimeZone(TimeZone.getDefault());
        return out.format(new Date(millis));
    }

    private static long parseIso(String iso) {
        if (TextUtils.isEmpty(iso)) return 0;
        try {
            String value = iso.length() >= 19 ? iso.substring(0, 19) : iso;
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = in.parse(value);
            return date == null ? 0 : date.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ApprovalView {
        final String id;
        final JSONObject approval;
        final TextView status;
        final TextView time;
        final TextView approve;
        final TextView reject;
        final long expiresAt;

        ApprovalView(String id, JSONObject approval, TextView status, TextView time,
                     TextView approve, TextView reject, long expiresAt) {
            this.id = id;
            this.approval = approval;
            this.status = status;
            this.time = time;
            this.approve = approve;
            this.reject = reject;
            this.expiresAt = expiresAt;
        }
    }
}
