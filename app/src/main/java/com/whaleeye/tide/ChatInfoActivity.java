package com.whaleeye.tide;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/** 私聊管理：AI 主页、Bot 设置、权限模式、免打扰与置顶。 */
public final class ChatInfoActivity extends Activity {
    public static final String EXTRA_CONVERSATION_ID = "conversation_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_POLICY = "policy";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;
    private TideClient client;
    private String conversationId;
    private String policy;
    private TextView policyValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.apply(this);
        session = new Session(this);
        conversationId = getIntent().getStringExtra(EXTRA_CONVERSATION_ID);
        policy = getIntent().getStringExtra(EXTRA_POLICY);
        if (policy == null || policy.isEmpty()) policy = "restricted";
        client = new TideClient(session);
        setContentView(R.layout.activity_chat_info);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        renderAiProfile();

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        findViewById(R.id.row_ai_profile).setOnClickListener(v ->
                startActivity(new Intent(this, AiProfileActivity.class)
                        .putExtra(AiProfileActivity.EXTRA_CONVERSATION_ID, conversationId)
                        .putExtra(AiProfileActivity.EXTRA_TITLE, title)));

        boolean admin = session.isAdmin();
        View inviteRow = findViewById(R.id.row_chat_invite);
        View inviteDivider = findViewById(R.id.divider_chat_invite);
        inviteRow.setVisibility(admin ? View.VISIBLE : View.GONE);
        inviteDivider.setVisibility(admin ? View.VISIBLE : View.GONE);
        inviteRow.setOnClickListener(v -> createInvite());
        View botRow = findViewById(R.id.row_bot_settings);
        View botDivider = findViewById(R.id.divider_bot_settings);
        View policyRow = findViewById(R.id.row_policy);
        View policyDivider = findViewById(R.id.divider_policy);
        botRow.setVisibility(admin ? View.VISIBLE : View.GONE);
        botDivider.setVisibility(admin ? View.VISIBLE : View.GONE);
        policyRow.setVisibility(admin ? View.VISIBLE : View.GONE);
        policyDivider.setVisibility(admin ? View.VISIBLE : View.GONE);
        botRow.setOnClickListener(v -> startActivity(new Intent(this, AdminSettingsActivity.class)));

        policyValue = findViewById(R.id.text_policy);
        policyValue.setText(policy);
        policyRow.setOnClickListener(v -> confirmPolicy());

        Switch mute = findViewById(R.id.switch_mute);
        Switch pin = findViewById(R.id.switch_pin);
        mute.setChecked(ChatPrefs.isMuted(this, conversationId));
        pin.setChecked(ChatPrefs.isPinned(this, conversationId));
        mute.setOnCheckedChangeListener((b, checked) ->
                ChatPrefs.setMuted(this, conversationId, checked));
        pin.setOnCheckedChangeListener((b, checked) ->
                ChatPrefs.setPinned(this, conversationId, checked));
    }

    private void createInvite() {
        if (!session.isAdmin()) return;
        new Thread(() -> {
            try {
                JSONObject response = client.createInvite(1440, 1);
                JSONObject invite = response.optJSONObject("invite");
                main.post(() -> showInvite(invite));
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this,
                        "创建失败：" + (e.getMessage() == null ? e.toString() : e.getMessage()),
                        Toast.LENGTH_LONG).show());
            }
        }, "tide-private-invite").start();
    }

    private void showInvite(JSONObject invite) {
        if (invite == null) return;
        String code = invite.optString("code", "");
        String joinUrl = invite.optString("joinUrl", "");
        String message = (joinUrl.isEmpty() ? "" : joinUrl + "\n\n") + "邀请码：" + code;
        new AlertDialog.Builder(this)
                .setTitle(R.string.chat_info_invite)
                .setMessage(message)
                .setPositiveButton(R.string.admin_invite_copy, (d, w) -> copy(
                        joinUrl.isEmpty() ? code : joinUrl))
                .setNeutralButton(R.string.group_invite_share, (d, w) ->
                        share(joinUrl.isEmpty() ? code : joinUrl))
                .setNegativeButton(R.string.admin_close, null)
                .show();
    }

    private void copy(String value) {
        ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("邀请", value));
            Toast.makeText(this, R.string.admin_copy_done, Toast.LENGTH_SHORT).show();
        }
    }

    private void share(String value) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, value);
        startActivity(Intent.createChooser(share, getString(R.string.group_invite_share)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderAiProfile();
    }

    private void renderAiProfile() {
        String name = AiProfileStore.name(this, "汐语");
        AvatarRenderer.applyAiCircle(
                (TextView) findViewById(R.id.chat_info_avatar), 64);
        ((TextView) findViewById(R.id.chat_info_name)).setText(name);
        String signature = AiProfileStore.signature(this);
        ((TextView) findViewById(R.id.chat_info_status)).setText(
                TextUtils.isEmpty(signature)
                        ? getString(R.string.ai_profile_status) : signature);
    }

    private void confirmPolicy() {
        String next = "owner".equals(policy) ? "restricted" : "owner";
        new AlertDialog.Builder(this)
                .setTitle(R.string.chat_info_policy)
                .setMessage(R.string.policy_confirm)
                .setPositiveButton(R.string.policy_switch_action, (d, w) -> {
                    policy = next;
                    policyValue.setText(next);
                    new Thread(() -> {
                        try {
                            client.setPolicy(conversationId, next);
                            main.post(() -> {
                                Toast.makeText(this,
                                        getString(R.string.policy_switched, next),
                                        Toast.LENGTH_SHORT).show();
                                TideService.startConversation(this, conversationId,
                                        getIntent().getStringExtra(EXTRA_TITLE));
                            });
                        } catch (Exception e) {
                            main.post(() -> Toast.makeText(this,
                                    getString(R.string.policy_failed,
                                            e.getMessage() == null ? e.toString() : e.getMessage()),
                                    Toast.LENGTH_LONG).show());
                        }
                    }, "tide-policy").start();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }
}
