package com.whaleeye.tide;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** 待审批的私聊/入群申请。 */
public final class AdminPendingActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;
    private TideClient client;
    private LinearLayout list;
    private TextView empty;
    private TextView error;
    private ProgressBar progress;
    private boolean loading;

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
        setContentView(R.layout.activity_admin_pending);
        list = findViewById(R.id.list_pending);
        empty = findViewById(R.id.text_pending_empty);
        error = findViewById(R.id.text_pending_error);
        progress = findViewById(R.id.progress_pending);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_pending_refresh).setOnClickListener(v -> load());
        error.setOnClickListener(v -> load());
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!loading) load();
    }

    private void load() {
        if (loading) return;
        loading = true;
        progress.setVisibility(View.VISIBLE);
        error.setVisibility(View.GONE);
        new Thread(() -> {
            try {
                JSONObject response = client.adminRequests();
                JSONArray items = response.optJSONArray("requests");
                main.post(() -> render(items == null ? new JSONArray() : items));
            } catch (Exception e) {
                main.post(() -> {
                    loading = false;
                    progress.setVisibility(View.GONE);
                    showError("待审批加载失败：" + message(e));
                });
            }
        }, "tide-admin-pending").start();
    }

    private void render(JSONArray items) {
        loading = false;
        progress.setVisibility(View.GONE);
        error.setVisibility(View.GONE);
        list.removeAllViews();
        empty.setVisibility(items.length() == 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < items.length(); i++) {
            JSONObject request = items.optJSONObject(i);
            if (request != null) list.addView(row(request));
        }
    }

    private View row(JSONObject request) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(12);
        card.setLayoutParams(params);

        String who = request.optString("displayName",
                request.optString("username", "?"));
        boolean group = "group".equals(request.optString("kind", "bot"));
        String action = group
                ? getString(R.string.admin_request_group,
                request.optString("conversationTitle", "群聊"))
                : getString(R.string.admin_request_bot);

        TextView title = text(who, 15, R.color.ink);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        TextView actionView = text(action, 12, R.color.ink_3);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(actionView);
        card.addView(header);

        TextView time = text(when(request.optString("createdAt", "")),
                11, R.color.ink_3);
        time.setPadding(0, dp(6), 0, 0);
        card.addView(time);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, 0);
        TextView reject = action(getString(R.string.admin_request_reject), false);
        TextView approve = action(getString(R.string.admin_request_approve), true);
        actions.addView(reject, new LinearLayout.LayoutParams(0, dp(40), 1f));
        LinearLayout.LayoutParams approveParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        approveParams.leftMargin = dp(10);
        actions.addView(approve, approveParams);
        card.addView(actions);

        String id = request.optString("id", "");
        approve.setOnClickListener(v -> decide(id, true));
        reject.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.admin_request_reject)
                .setMessage("拒绝这条申请？")
                .setPositiveButton(R.string.admin_request_reject,
                        (d, w) -> decide(id, false))
                .setNegativeButton(R.string.action_cancel, null)
                .show());
        return card;
    }

    private void decide(String id, boolean approve) {
        new Thread(() -> {
            try {
                if (approve) client.approveRequest(id);
                else client.rejectRequest(id);
                main.post(() -> {
                    Toast.makeText(this,
                            approve ? "已批准" : "已拒绝", Toast.LENGTH_SHORT).show();
                    load();
                });
            } catch (TideClient.TideException e) {
                if ("user-disabled".equals(e.code)) {
                    main.post(() -> {
                        Toast.makeText(this, R.string.admin_request_disabled,
                                Toast.LENGTH_LONG).show();
                        load();
                    });
                    return;
                }
                if ("request-not-found".equals(e.code)) {
                    main.post(this::load);
                    return;
                }
                main.post(() -> showError("处理失败：" + message(e)));
            } catch (Exception e) {
                main.post(() -> showError("处理失败：" + message(e)));
            }
        }, "tide-admin-decide").start();
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

    private TextView action(String value, boolean primary) {
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

    private static String when(String iso) {
        if (iso == null || iso.length() < 19) return "";
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = in.parse(iso.substring(0, 19));
            if (date == null) return "";
            SimpleDateFormat out = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
            out.setTimeZone(TimeZone.getDefault());
            return out.format(date);
        } catch (Exception e) {
            return "";
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
