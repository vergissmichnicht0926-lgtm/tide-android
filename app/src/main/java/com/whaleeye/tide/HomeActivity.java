package com.whaleeye.tide;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** 管理员首页：待审批 / 用户 / 邀请码 / 群聊 / Bot 设置。 */
public final class HomeActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;
    private TideClient client;
    private TextView pendingValue;
    private TextView usersValue;
    private TextView invitesValue;

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
        setContentView(R.layout.activity_admin);
        pendingValue = findViewById(R.id.text_pending_value);
        usersValue = findViewById(R.id.text_users_value);
        invitesValue = findViewById(R.id.text_invites_value);

        findViewById(R.id.btn_admin_refresh).setOnClickListener(v -> loadCounts());
        findViewById(R.id.row_pending).setOnClickListener(v ->
                startActivity(new Intent(this, AdminPendingActivity.class)));
        findViewById(R.id.row_users).setOnClickListener(v ->
                startActivity(new Intent(this, AdminUsersActivity.class)));
        findViewById(R.id.row_invites).setOnClickListener(v ->
                startActivity(new Intent(this, AdminInvitesActivity.class)));
        findViewById(R.id.row_settings).setOnClickListener(v ->
                startActivity(new Intent(this, AdminSettingsActivity.class)));
        findViewById(R.id.row_devices).setOnClickListener(v ->
                startActivity(new Intent(this, DeviceActivity.class)));
        BottomNav.bind(this, BottomNav.TAB_MANAGE);
        loadCounts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (client != null) loadCounts();
    }

    private void loadCounts() {
        new Thread(() -> {
            int pending = 0;
            int users = 0;
            int invites = 0;
            try {
                JSONObject requests = client.adminRequests();
                JSONArray requestItems = requests.optJSONArray("requests");
                pending = requestItems == null ? 0 : requestItems.length();
            } catch (Exception ignored) {
            }
            try {
                JSONObject response = client.adminUsers();
                JSONArray items = response.optJSONArray("users");
                users = items == null ? 0 : items.length();
            } catch (Exception ignored) {
            }
            try {
                JSONObject response = client.adminInvites();
                JSONArray items = response.optJSONArray("invites");
                invites = items == null ? 0 : items.length();
            } catch (Exception ignored) {
            }
            final int p = pending;
            final int u = users;
            final int i = invites;
            main.post(() -> {
                pendingValue.setText(p > 0 ? p + " 条" : "");
                usersValue.setText(u > 0 ? u + " 人" : "");
                invitesValue.setText(i > 0 ? i + " 个" : "");
            });
        }, "tide-admin-counts").start();
    }

}
