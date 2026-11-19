package com.whaleeye.tide;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 群成员主页：群昵称、账号、群内角色、加入时间。 */
public final class MemberProfileActivity extends Activity {
    public static final String EXTRA_CONVERSATION_ID = "conversation_id";
    public static final String EXTRA_USER_ID = "user_id";
    public static final String EXTRA_NAME = "name";
    public static final String EXTRA_GROUP_TITLE = "group_title";

    private final Handler main = new Handler(Looper.getMainLooper());
    private TideClient client;
    private String conversationId;
    private String userId;
    private String fallbackName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.apply(this);
        setContentView(R.layout.activity_member_profile);
        conversationId = getIntent().getStringExtra(EXTRA_CONVERSATION_ID);
        userId = getIntent().getStringExtra(EXTRA_USER_ID);
        fallbackName = getIntent().getStringExtra(EXTRA_NAME);
        String groupTitle = getIntent().getStringExtra(EXTRA_GROUP_TITLE);
        if (TextUtils.isEmpty(conversationId) || TextUtils.isEmpty(userId)) {
            finish();
            return;
        }
        client = new TideClient(new Session(this));
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        if (!TextUtils.isEmpty(groupTitle)) {
            ((TextView) findViewById(R.id.member_group))
                    .setText(getString(R.string.member_profile_group, groupTitle));
        } else {
            findViewById(R.id.member_group).setVisibility(View.GONE);
        }
        render(fallbackName, null, "member", 0L, false, null);
        load();
    }

    private void load() {
        new Thread(() -> {
            try {
                JSONObject response = client.memberProfile(conversationId, userId);
                final JSONObject member = response.optJSONObject("member");
                main.post(() -> {
                    if (isFinishing()) return;
                    if (member == null) {
                        render(fallbackName, null, "member", 0L, false, null);
                    } else {
                        render(
                                member.optString("display_name", fallbackName),
                                member.optString("username", null),
                                member.optString("role", "member"),
                                member.optLong("joined_at", 0L),
                                member.optBoolean("has_avatar", false),
                                member.optString("avatar_version", null));
                    }
                });
            } catch (Exception e) {
                main.post(() -> render(fallbackName, null, "member", 0L, false, null));
            }
        }, "tide-member-profile").start();
    }

    private void render(String name, String username, String role, long joinedAt,
                        boolean hasAvatar, String avatarVersion) {
        String display = TextUtils.isEmpty(name) ? "成员" : name;
        TextView avatar = findViewById(R.id.member_avatar);
        avatar.setText(display.substring(0, 1));
        avatar.setBackgroundResource(R.drawable.bg_avatar_square_soft);
        if (hasAvatar) {
            UserAvatarLoader.apply(avatar, 84, userId, avatarVersion, false);
        }
        ((TextView) findViewById(R.id.member_name)).setText(display);

        TextView account = findViewById(R.id.member_username);
        if (TextUtils.isEmpty(username) || username.equals(display)) {
            account.setVisibility(View.GONE);
        } else {
            account.setVisibility(View.VISIBLE);
            account.setText("@" + username);
        }

        TextView roleView = findViewById(R.id.member_role);
        if ("owner".equals(role)) {
            roleView.setText(R.string.group_owner);
        } else if ("admin".equals(role)) {
            roleView.setText(R.string.group_admin_badge);
        } else {
            roleView.setText(R.string.group_member);
        }

        TextView joined = findViewById(R.id.member_joined);
        if (joinedAt > 0) {
            joined.setVisibility(View.VISIBLE);
            joined.setText(getString(R.string.member_profile_joined,
                    new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                            .format(new Date(joinedAt))));
        } else {
            joined.setVisibility(View.GONE);
        }
    }
}
