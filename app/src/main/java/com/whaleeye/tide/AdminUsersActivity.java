package com.whaleeye.tide;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/** 管理员用户列表，只显示公开资料与用量。 */
public final class AdminUsersActivity extends Activity {
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
        setContentView(R.layout.activity_admin_users);
        list = findViewById(R.id.list_users);
        empty = findViewById(R.id.text_users_empty);
        error = findViewById(R.id.text_users_error);
        progress = findViewById(R.id.progress_users);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_users_refresh).setOnClickListener(v -> load());
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
                JSONObject response = client.adminUsers();
                JSONArray items = response.optJSONArray("users");
                main.post(() -> render(items == null ? new JSONArray() : items));
            } catch (Exception e) {
                main.post(() -> {
                    loading = false;
                    progress.setVisibility(View.GONE);
                    showError("用户加载失败：" + message(e));
                });
            }
        }, "tide-admin-users").start();
    }

    private void render(JSONArray items) {
        loading = false;
        progress.setVisibility(View.GONE);
        error.setVisibility(View.GONE);
        list.removeAllViews();
        empty.setVisibility(items.length() == 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < items.length(); i++) {
            JSONObject user = items.optJSONObject(i);
            if (user != null) list.addView(row(user));
        }
    }

    private View row(JSONObject user) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8);
        card.setLayoutParams(params);
        card.setClickable(true);
        card.setFocusable(true);

        String name = user.optString("displayName", user.optString("username", "?"));
        TextView avatar = new TextView(this);
        avatar.setText(name.substring(0, 1));
        avatar.setTextSize(16);
        avatar.setTextColor(getResources().getColor(R.color.paper));
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackgroundResource(R.drawable.bg_avatar_teal);
        card.addView(avatar, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(12), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(15);
        title.setTextColor(getResources().getColor(R.color.ink));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        column.addView(title);
        TextView meta = new TextView(this);
        meta.setText("@" + user.optString("username", "")
                + " · " + role(user.optString("role", "guest")));
        meta.setTextSize(11);
        meta.setTextColor(getResources().getColor(R.color.ink_3));
        meta.setPadding(0, dp(3), 0, 0);
        column.addView(meta);
        card.addView(column, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView stats = new TextView(this);
        stats.setText("今日 " + user.optInt("todayMessages", 0) + " 条");
        stats.setTextSize(11);
        stats.setTextColor(getResources().getColor(R.color.ink_3));
        card.addView(stats);
        ImageView chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_right);
        chevron.setImageTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.ink_3)));
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(dp(18), dp(18));
        chevronParams.leftMargin = dp(6);
        card.addView(chevron, chevronParams);

        card.setOnClickListener(v -> startActivity(new Intent(this, AdminUserDetailActivity.class)
                .putExtra(AdminUserDetailActivity.EXTRA_USER_JSON, user.toString())));
        return card;
    }

    private String role(String value) {
        return "admin".equals(value)
                ? getString(R.string.admin_role_admin)
                : getString(R.string.admin_role_guest);
    }

    private void showError(String message) {
        error.setText(message + " · " + getString(R.string.messages_retry));
        error.setVisibility(View.VISIBLE);
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
