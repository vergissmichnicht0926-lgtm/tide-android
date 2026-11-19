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

/** 邀请码列表、创建、复制/分享、撤销。 */
public final class AdminInvitesActivity extends Activity {
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
        setContentView(R.layout.activity_admin_invites);
        list = findViewById(R.id.list_invites);
        empty = findViewById(R.id.text_invites_empty);
        error = findViewById(R.id.text_invites_error);
        progress = findViewById(R.id.progress_invites);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_create_invite).setOnClickListener(v -> chooseType());
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
                JSONObject response = client.adminInvites();
                JSONArray items = response.optJSONArray("invites");
                main.post(() -> render(items == null ? new JSONArray() : items));
            } catch (Exception e) {
                main.post(() -> {
                    loading = false;
                    progress.setVisibility(View.GONE);
                    showError("邀请码加载失败：" + message(e));
                });
            }
        }, "tide-admin-invites").start();
    }

    private void render(JSONArray items) {
        loading = false;
        progress.setVisibility(View.GONE);
        error.setVisibility(View.GONE);
        list.removeAllViews();
        empty.setVisibility(items.length() == 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < items.length(); i++) {
            JSONObject invite = items.optJSONObject(i);
            if (invite != null) list.addView(row(invite));
        }
    }

    private View row(JSONObject invite) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(10);
        card.setLayoutParams(params);

        final String codeText = invite.optString("code", "");
        final String best = bestShareUrl(invite);
        String status = status(invite);
        boolean active = getString(R.string.admin_invite_active).equals(status);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView code = text(best.isEmpty() ? codeText : best,
                best.isEmpty() ? 16 : 13, R.color.ink);
        if (best.isEmpty()) {
            code.setTypeface(null, android.graphics.Typeface.BOLD);
            code.setLetterSpacing(0.06f);
        } else {
            code.setMaxLines(1);
            code.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        }
        header.addView(code, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView statusView = text(status, 11, active ? R.color.teal : R.color.ink_3);
        statusView.setBackgroundResource(R.drawable.bg_chip);
        statusView.setPadding(dp(8), dp(4), dp(8), dp(4));
        header.addView(statusView);
        card.addView(header);

        String kind = "group".equals(invite.optString("kind", "bot"))
                ? getString(R.string.admin_invite_group)
                : getString(R.string.admin_invite_bot);
        TextView meta = text(kind + " · " + getString(R.string.admin_invite_uses,
                        invite.optInt("usedCount", 0), invite.optInt("maxUses", 1)),
                11, R.color.ink_3);
        meta.setPadding(0, dp(6), 0, 0);
        card.addView(meta);
        TextView expiry = text("有效期至 " + when(invite.optString("expiresAt", "")),
                11, R.color.ink_3);
        expiry.setPadding(0, dp(2), 0, 0);
        card.addView(expiry);

        if (active) {
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setPadding(0, dp(10), 0, 0);
            final String shareValue = best.isEmpty() ? codeText : best;
            TextView copy = action(getString(R.string.admin_invite_copy));
            actions.addView(copy, new LinearLayout.LayoutParams(0, dp(38), 1f));
            TextView share = action(getString(R.string.admin_invite_share));
            LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(0, dp(38), 1f);
            shareParams.leftMargin = dp(8);
            actions.addView(share, shareParams);
            share.setOnClickListener(v -> share(shareValue));
            TextView revoke = action(getString(R.string.admin_invite_revoke));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(38), 1f);
            p.leftMargin = dp(8);
            actions.addView(revoke, p);
            revoke.setOnClickListener(v -> confirmRevoke(invite));
            card.addView(actions);
            if (best.isEmpty()) {
                TextView hint = text(getString(R.string.admin_invite_no_public_url), 10, R.color.ink_3);
                hint.setPadding(0, dp(6), 0, 0);
                card.addView(hint);
            }
            copy.setOnClickListener(v -> copy(shareValue));
        }
        return card;
    }

    private void chooseType() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.admin_invite_create_title)
                .setItems(new String[]{
                        getString(R.string.admin_invite_bot),
                        getString(R.string.admin_invite_group)
                }, (d, which) -> {
                    if (which == 0) createBotInvite();
                    else chooseGroup();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void createBotInvite() {
        new Thread(() -> {
            try {
                JSONObject response = client.createInvite(1440, 1);
                JSONObject invite = response.optJSONObject("invite");
                main.post(() -> showResult(invite));
            } catch (Exception e) {
                main.post(() -> showError("创建失败：" + message(e)));
            }
        }, "tide-create-bot-invite").start();
    }

    private void chooseGroup() {
        new Thread(() -> {
            try {
                JSONObject response = client.adminGroups();
                JSONArray groups = response.optJSONArray("groups");
                if (groups == null || groups.length() == 0) {
                    main.post(() -> Toast.makeText(this,
                            "还没有群", Toast.LENGTH_SHORT).show());
                    return;
                }
                final JSONObject[] values = new JSONObject[groups.length()];
                final String[] labels = new String[groups.length()];
                for (int i = 0; i < groups.length(); i++) {
                    values[i] = groups.optJSONObject(i);
                    labels[i] = values[i].optString("title", "群聊");
                }
                main.post(() -> new AlertDialog.Builder(this)
                        .setTitle(R.string.admin_invite_group_choose)
                        .setItems(labels, (d, which) ->
                                createGroupInvite(values[which]))
                        .setNegativeButton(R.string.action_cancel, null)
                        .show());
            } catch (Exception e) {
                main.post(() -> showError("群列表加载失败：" + message(e)));
            }
        }, "tide-admin-groups").start();
    }

    private void createGroupInvite(JSONObject group) {
        if (group == null) return;
        String groupId = group.optString("id", "");
        new Thread(() -> {
            try {
                JSONObject response = client.createGroupInvite(groupId, 1440, 1);
                JSONObject invite = response.optJSONObject("invite");
                main.post(() -> showResult(invite));
            } catch (Exception e) {
                main.post(() -> showError("创建失败：" + message(e)));
            }
        }, "tide-create-group-invite").start();
    }

    private void showResult(JSONObject invite) {
        if (invite == null) {
            showError("创建失败：响应为空");
            return;
        }
        final String code = invite.optString("code", "");
        final String best = bestShareUrl(invite);
        final String shareValue = best.isEmpty() ? code : best;
        String message = best.isEmpty()
                ? "邀请码：" + code + "\n\n24 小时内有效，默认 1 次。"
                : best + "\n\n24 小时内有效，默认 1 次。";
        if (best.isEmpty()) {
            message += "\n\n" + getString(R.string.admin_invite_no_public_url);
        }
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextIsSelectable(true);
        text.setTextSize(14);
        text.setPadding(dp(20), dp(8), dp(20), dp(8));
        new AlertDialog.Builder(this)
                .setTitle(R.string.admin_invite_create_title)
                .setView(text)
                .setPositiveButton(R.string.admin_invite_copy, (d, w) -> copy(shareValue))
                .setNeutralButton(R.string.admin_invite_share, (d, w) -> share(shareValue))
                .setNegativeButton(R.string.admin_close, null)
                .show();
        load();
    }

    private void confirmRevoke(JSONObject invite) {
        String id = invite.optString("id", "");
        new AlertDialog.Builder(this)
                .setTitle(R.string.admin_invite_revoke)
                .setMessage("撤销后这个邀请码不能再使用。")
                .setPositiveButton(R.string.admin_invite_revoke, (d, w) ->
                        new Thread(() -> {
                            try {
                                client.revokeInvite(id);
                                main.post(() -> {
                                    Toast.makeText(this, "已撤销",
                                            Toast.LENGTH_SHORT).show();
                                    load();
                                });
                            } catch (Exception e) {
                                main.post(() -> showError("撤销失败：" + message(e)));
                            }
                        }, "tide-revoke-invite").start())
                .setNegativeButton(R.string.action_cancel, null)
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

    private static String textOf(JSONObject invite, String key) {
        String value = invite.optString(key, "");
        return "null".equals(value) ? "" : value;
    }

    private static String bestShareUrl(JSONObject invite) {
        String web = textOf(invite, "webUrl");
        if (!web.isEmpty()) return web;
        return textOf(invite, "joinUrl");
    }

    private void share(String value) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, value);
        startActivity(Intent.createChooser(share, getString(R.string.admin_invite_share)));
    }

    private String status(JSONObject invite) {
        if (!invite.optString("revokedAt", "").isEmpty()
                && !"null".equals(invite.optString("revokedAt", ""))) {
            return getString(R.string.admin_invite_revoked);
        }
        long expires = parseIso(invite.optString("expiresAt", ""));
        if (expires > 0 && expires <= System.currentTimeMillis()) {
            return getString(R.string.admin_invite_expired);
        }
        if (invite.optInt("usedCount", 0) >= invite.optInt("maxUses", 1)) {
            return getString(R.string.admin_invite_used);
        }
        return getString(R.string.admin_invite_active);
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

    private TextView action(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(13);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(getResources().getColor(R.color.ink));
        view.setBackgroundResource(R.drawable.bg_secondary);
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private static long parseIso(String iso) {
        if (iso == null || iso.length() < 19) return 0;
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = in.parse(iso.substring(0, 19));
            return date == null ? 0 : date.getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static String when(String iso) {
        long millis = parseIso(iso);
        if (millis <= 0) return "未知";
        SimpleDateFormat out = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        out.setTimeZone(TimeZone.getDefault());
        return out.format(new Date(millis));
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
