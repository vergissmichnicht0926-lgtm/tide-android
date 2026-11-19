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
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** 群聊管理：成员、群邀请、免打扰、置顶、退出群聊。 */
public final class GroupInfoActivity extends Activity {
    public static final String EXTRA_CONVERSATION_ID = "conversation_id";
    public static final String EXTRA_TITLE = "title";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;
    private TideClient client;
    private String conversationId;
    private String title;
    private String userId;
    private boolean owner;
    private boolean admin;
    private boolean selfAdmin;
    private String announcement;
    private LinearLayout memberList;
    private TextView groupCount;
    private TextView note;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.apply(this);
        session = new Session(this);
        admin = session.isAdmin();
        client = new TideClient(session);
        conversationId = getIntent().getStringExtra(EXTRA_CONVERSATION_ID);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        if (TextUtils.isEmpty(title)) title = "群聊";
        setContentView(R.layout.activity_group_info);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.group_name)).setText(title);
        TextView avatar = findViewById(R.id.group_avatar);
        avatar.setText(title.substring(0, 1));
        groupCount = findViewById(R.id.group_count);
        memberList = findViewById(R.id.list_members);
        note = findViewById(R.id.text_group_note);

        Switch mute = findViewById(R.id.switch_mute);
        Switch pin = findViewById(R.id.switch_pin);
        mute.setChecked(ChatPrefs.isMuted(this, conversationId));
        pin.setChecked(ChatPrefs.isPinned(this, conversationId));
        mute.setOnCheckedChangeListener((b, checked) ->
                ChatPrefs.setMuted(this, conversationId, checked));
        pin.setOnCheckedChangeListener((b, checked) ->
                ChatPrefs.setPinned(this, conversationId, checked));

        findViewById(R.id.row_group_invite).setOnClickListener(v -> createInvite());
        findViewById(R.id.row_group_invite).setVisibility(admin ? View.VISIBLE : View.GONE);
        findViewById(R.id.divider_group_invite).setVisibility(admin ? View.VISIBLE : View.GONE);
        findViewById(R.id.row_group_name).setOnClickListener(v -> renameGroup());
        findViewById(R.id.row_group_announcement).setOnClickListener(v -> editAnnouncement());
        findViewById(R.id.row_dissolve).setOnClickListener(v -> dissolveGroup());
        findViewById(R.id.row_group_name).setVisibility(View.GONE);
        findViewById(R.id.divider_group_name).setVisibility(View.GONE);
        findViewById(R.id.row_group_announcement).setVisibility(View.GONE);
        findViewById(R.id.divider_group_announcement).setVisibility(View.GONE);
        findViewById(R.id.row_leave).setOnClickListener(v -> leave());
        load();
    }

    private void load() {
        new Thread(() -> {
            try {
                if (TextUtils.isEmpty(session.userId())) {
                    JSONObject me = client.me();
                    String id = firstNonNull(me, "userId", "user_id");
                    if (id != null) {
                        userId = id;
                        session.saveUserId(id);
                    }
                } else {
                    userId = session.userId();
                }
                JSONObject response = client.listMembers(conversationId);
                JSONArray members = response.optJSONArray("members");
                String loadedTitle = title;
                String loadedAnnouncement = null;
                try {
                    JSONObject conversations = client.listConversations();
                    JSONArray rows = conversations.optJSONArray("conversations");
                    if (rows != null) {
                        for (int i = 0; i < rows.length(); i++) {
                            JSONObject row = rows.optJSONObject(i);
                            if (row == null || !conversationId.equals(row.optString("id", ""))) {
                                continue;
                            }
                            loadedTitle = row.optString("title", loadedTitle);
                            loadedAnnouncement = row.isNull("announcement")
                                    ? null : row.optString("announcement", null);
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
                final String nextTitle = loadedTitle;
                final String nextAnnouncement = loadedAnnouncement;
                main.post(() -> {
                    title = nextTitle;
                    announcement = nextAnnouncement;
                    ((TextView) findViewById(R.id.group_name)).setText(title);
                    render(members == null ? new JSONArray() : members);
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this,
                        "成员加载失败：" + message(e), Toast.LENGTH_LONG).show());
            }
        }, "tide-group-info").start();
    }

    private void render(JSONArray members) {
        memberList.removeAllViews();
        groupCount.setText(getString(R.string.group_member_count, members.length()));
        owner = false;
        selfAdmin = false;
        for (int i = 0; i < members.length(); i++) {
            JSONObject member = members.optJSONObject(i);
            if (member == null) continue;
            String id = member.optString("user_id", "");
            boolean isOwner = "owner".equals(member.optString("role", ""));
            if (isOwner && id.equals(userId)) owner = true;
            if (id.equals(userId) && "admin".equals(member.optString("role", ""))) {
                selfAdmin = true;
            }
        }
        for (int i = 0; i < members.length(); i++) {
            JSONObject member = members.optJSONObject(i);
            if (member == null) continue;
            boolean isOwner = "owner".equals(member.optString("role", ""));
            memberList.addView(memberCell(member, isOwner));
        }
        if (admin) memberList.addView(inviteCell());
        note.setVisibility(owner ? View.VISIBLE : View.GONE);
        if (owner) {
            note.setText(R.string.group_leave_owner);
            findViewById(R.id.row_leave).setAlpha(0.45f);
        }
        boolean canManage = owner || selfAdmin;
        findViewById(R.id.row_group_name).setVisibility(owner ? View.VISIBLE : View.GONE);
        findViewById(R.id.divider_group_name).setVisibility(owner ? View.VISIBLE : View.GONE);
        findViewById(R.id.row_group_announcement)
                .setVisibility(canManage ? View.VISIBLE : View.GONE);
        findViewById(R.id.divider_group_announcement)
                .setVisibility(canManage ? View.VISIBLE : View.GONE);
        findViewById(R.id.row_dissolve).setVisibility(owner ? View.VISIBLE : View.GONE);
        findViewById(R.id.divider_group_dissolve).setVisibility(owner ? View.VISIBLE : View.GONE);
    }

    private View memberCell(JSONObject member, boolean isOwner) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(60), dp(84));
        params.rightMargin = dp(8);
        cell.setLayoutParams(params);

        String id = member.optString("user_id", "");
        String name = member.optString("display_name", "?");
        TextView avatar = new TextView(this);
        avatar.setTextSize(17);
        avatar.setTextColor(getResources().getColor(R.color.paper));
        avatar.setGravity(Gravity.CENTER);
        if (id.equals(userId)) {
            AvatarRenderer.applyUserSquare(avatar, 48,
                    TextUtils.isEmpty(name) ? "?" : name.substring(0, 1));
        } else {
            avatar.setText(TextUtils.isEmpty(name) ? "?" : name.substring(0, 1));
            avatar.setBackgroundResource(R.drawable.bg_avatar_square_soft);
            if (member.optBoolean("has_avatar", false)) {
                UserAvatarLoader.apply(avatar, 48, id,
                        member.optString("avatar_version", null), false);
            }
        }
        cell.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(11);
        label.setTextColor(getResources().getColor(R.color.ink_2));
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(label, new LinearLayout.LayoutParams(dp(60), dp(20)));

        if (isOwner) {
            TextView role = new TextView(this);
            role.setText(R.string.group_owner);
            role.setTextSize(9);
            role.setTextColor(getResources().getColor(R.color.teal));
            role.setGravity(Gravity.CENTER);
            cell.addView(role);
        }
        String role = member.optString("role", "member");
        cell.setOnClickListener(v -> {
            if (id.equals(userId)) {
                startActivity(new Intent(this, ProfileActivity.class));
            } else {
                startActivity(new Intent(this, MemberProfileActivity.class)
                        .putExtra(MemberProfileActivity.EXTRA_CONVERSATION_ID, conversationId)
                        .putExtra(MemberProfileActivity.EXTRA_USER_ID, id)
                        .putExtra(MemberProfileActivity.EXTRA_NAME, name)
                        .putExtra(MemberProfileActivity.EXTRA_GROUP_TITLE, title));
            }
        });
        if (!id.equals(userId) && (owner || (selfAdmin && "member".equals(role)))) {
            cell.setOnLongClickListener(v -> {
                showMemberActions(member);
                return true;
            });
        }
        return cell;
    }

    private View inviteCell() {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(60), dp(84));
        cell.setLayoutParams(params);
        TextView plus = new TextView(this);
        plus.setText("＋");
        plus.setTextSize(22);
        plus.setTextColor(getResources().getColor(R.color.teal));
        plus.setGravity(Gravity.CENTER);
        plus.setBackgroundResource(R.drawable.bg_chip);
        cell.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView label = new TextView(this);
        label.setText(R.string.group_invite_create);
        label.setTextSize(10);
        label.setTextColor(getResources().getColor(R.color.ink_3));
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        cell.addView(label, new LinearLayout.LayoutParams(dp(60), dp(20)));
        cell.setOnClickListener(v -> createInvite());
        return cell;
    }

    private void renameGroup() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(title);
        input.setSelection(input.getText().length());
        input.setHint(R.string.group_rename_hint);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.group_rename)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty() || value.length() > 30) {
                        Toast.makeText(this, "群名称必须是 1~30 字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new Thread(() -> {
                        try {
                            client.updateConversation(conversationId, value, null);
                            main.post(() -> {
                                title = value;
                                ((TextView) findViewById(R.id.group_name)).setText(value);
                                Toast.makeText(this, "群名称已更新", Toast.LENGTH_SHORT).show();
                            });
                        } catch (Exception e) {
                            main.post(() -> Toast.makeText(this,
                                    getString(R.string.group_action_failed, message(e)),
                                    Toast.LENGTH_LONG).show());
                        }
                    }, "tide-group-rename").start();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void editAnnouncement() {
        EditText input = new EditText(this);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setMinLines(3);
        input.setMaxLines(6);
        input.setHint(R.string.group_announcement_hint);
        input.setText(announcement == null ? "" : announcement);
        input.setSelection(input.getText().length());
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(R.string.group_announcement)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (value.length() > 500) {
                        Toast.makeText(this, "群公告最多 500 字", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new Thread(() -> {
                        try {
                            client.updateConversation(conversationId, null, value);
                            main.post(() -> {
                                announcement = value.isEmpty() ? null : value;
                                Toast.makeText(this, "群公告已更新", Toast.LENGTH_SHORT).show();
                            });
                        } catch (Exception e) {
                            main.post(() -> Toast.makeText(this,
                                    getString(R.string.group_action_failed, message(e)),
                                    Toast.LENGTH_LONG).show());
                        }
                    }, "tide-group-announcement").start();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void dissolveGroup() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.group_dissolve)
                .setMessage(R.string.group_dissolve_confirm)
                .setPositiveButton(R.string.group_dissolve, (d, w) -> new Thread(() -> {
                    try {
                        client.dissolveGroup(conversationId);
                        main.post(() -> {
                            startActivity(new Intent(this, MessagesActivity.class)
                                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                            | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                            finish();
                        });
                    } catch (Exception e) {
                        main.post(() -> Toast.makeText(this,
                                getString(R.string.group_action_failed, message(e)),
                                Toast.LENGTH_LONG).show());
                    }
                }, "tide-group-dissolve").start())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showMemberActions(JSONObject member) {
        String id = member.optString("user_id", "");
        String name = member.optString("display_name", id);
        String role = member.optString("role", "member");
        if ("owner".equals(role) || id.equals(userId)) return;

        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<Runnable> actions = new java.util.ArrayList<>();
        if (owner) {
            boolean isAdmin = "admin".equals(role);
            labels.add(getString(isAdmin
                    ? R.string.group_role_member : R.string.group_role_admin));
            actions.add(() -> changeRole(id, isAdmin ? "member" : "admin"));
        }
        if (owner || selfAdmin) {
            labels.add("移出群聊");
            actions.add(() -> removeMember(id));
        }
        if (labels.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void changeRole(String targetId, String role) {
        new Thread(() -> {
            try {
                client.setMemberRole(conversationId, targetId, role);
                main.post(this::load);
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this,
                        getString(R.string.group_action_failed, message(e)),
                        Toast.LENGTH_LONG).show());
            }
        }, "tide-group-role").start();
    }

    private void removeMember(String targetId) {
        new Thread(() -> {
            try {
                client.removeMember(conversationId, targetId);
                main.post(this::load);
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this,
                        getString(R.string.group_action_failed, message(e)),
                        Toast.LENGTH_LONG).show());
            }
        }, "tide-group-remove").start();
    }

    private void createInvite() {
        if (!admin) {
            Toast.makeText(this, "只有管理员能创建群邀请", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                JSONObject response = client.createGroupInvite(conversationId, 1440, 1);
                JSONObject invite = response.optJSONObject("invite");
                main.post(() -> showInvite(invite));
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this,
                        "创建邀请失败：" + message(e), Toast.LENGTH_LONG).show());
            }
        }, "tide-group-invite").start();
    }

    private void showInvite(JSONObject invite) {
        if (invite == null) return;
        String code = invite.optString("code", "");
        String joinUrl = invite.optString("joinUrl", "");
        String message = (joinUrl.isEmpty() ? "" : joinUrl + "\n\n") + "邀请码：" + code;
        new AlertDialog.Builder(this)
                .setTitle(R.string.group_invite)
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
            clipboard.setPrimaryClip(ClipData.newPlainText("群邀请", value));
            Toast.makeText(this, R.string.group_invite_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private void share(String value) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, value);
        startActivity(Intent.createChooser(share, getString(R.string.group_invite_share)));
    }

    private void leave() {
        if (owner) {
            Toast.makeText(this, R.string.group_leave_owner, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.group_leave)
                .setMessage(R.string.group_leave_confirm)
                .setPositiveButton(R.string.group_leave, (d, w) ->
                        new Thread(() -> {
                            try {
                                client.removeMember(conversationId, userId);
                                main.post(() -> {
                                    TideService.stop(this);
                                    startActivity(new Intent(this, MessagesActivity.class)
                                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                                    finish();
                                });
                            } catch (Exception e) {
                                main.post(() -> Toast.makeText(this,
                                        "退出失败：" + message(e), Toast.LENGTH_LONG).show());
                            }
                        }, "tide-group-leave").start())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static String firstNonNull(JSONObject object, String first, String second) {
        String value = object.optString(first, null);
        if (value == null || value.isEmpty() || "null".equals(value)) {
            value = object.optString(second, null);
        }
        if (value == null || value.isEmpty() || "null".equals(value)) return null;
        return value;
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
