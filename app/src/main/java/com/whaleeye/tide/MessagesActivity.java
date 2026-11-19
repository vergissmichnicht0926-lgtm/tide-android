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
import android.net.Uri;
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
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 主会话列表：顶栏、消息/动态/管理底栏、加号面板。 */
public final class MessagesActivity extends Activity implements TideService.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConversationAdapter adapter;

    private Session session;
    private TideClient client;
    private ListView list;
    private LinearLayout empty;
    private TextView textHomeName;
    private TextView textHomeConn;
    private TextView textHomeError;
    private FrameLayout avatarHome;
    private ImageView avatarImage;
    private TextView avatarInitial;
    private android.widget.ProgressBar progress;
    private boolean loading;
    private boolean profileLoading;
    private final Runnable refreshUnread = () -> {
        if (!loading) loadConversations();
    };

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
        setContentView(R.layout.activity_messages);
        adapter = new ConversationAdapter(this);
        refreshBotProfile();

        avatarHome = findViewById(R.id.avatar_home);
        avatarImage = findViewById(R.id.avatar_home_image);
        avatarInitial = findViewById(R.id.avatar_home_initial);
        textHomeName = findViewById(R.id.text_home_name);
        textHomeConn = findViewById(R.id.text_home_conn);
        textHomeError = findViewById(R.id.text_home_error);
        progress = findViewById(R.id.progress_home);
        list = findViewById(R.id.list_conversations);
        empty = findViewById(R.id.empty_messages);
        list.setAdapter(adapter);

        applyProfile();
        findViewById(R.id.home_identity).setOnClickListener(v -> openProfile());
        avatarHome.setOnClickListener(v -> openProfile());
        findViewById(R.id.btn_home_add).setOnClickListener(v -> showAddSheet());
        findViewById(R.id.btn_empty_add).setOnClickListener(v -> showAddSheet());
        list.setOnItemClickListener((parent, view, position, id) -> {
            Conversation conversation = adapter.getItem(position);
            openChat(conversation.id, conversation.title);
        });

        BottomNav.bind(this, BottomNav.TAB_MESSAGES);

        updateConnection(TideService.currentState());
        loadConversations();
        String pendingJoinCode = getIntent().getStringExtra(MainActivity.EXTRA_JOIN_CODE);
        String pendingJoinKind = getIntent().getStringExtra(MainActivity.EXTRA_JOIN_KIND);
        if (!TextUtils.isEmpty(pendingJoinCode) && "group".equals(pendingJoinKind)) {
            getIntent().removeExtra(MainActivity.EXTRA_JOIN_CODE);
            main.postDelayed(() -> acceptGroupCode(pendingJoinCode), 300);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        TideService.addListener(this);
        if (session != null && session.hasLogin()) {
            TideService.start(this);
        }
        updateConnection(TideService.currentState());
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyProfile();
        refreshBotProfile();
        if (!loading) loadConversations();
    }

    private void refreshBotProfile() {
        if (profileLoading || client == null) return;
        profileLoading = true;
        new Thread(() -> {
            try {
                JSONObject profile = client.botProfile();
                AiProfileStore.apply(this, profile);
            } catch (Exception ignored) {
            } finally {
                main.post(() -> {
                    profileLoading = false;
                    if (adapter != null) adapter.notifyDataSetChanged();
                });
            }
        }, "tide-bot-profile").start();
    }

    private void applyProfile() {
        String display = ProfileStore.displayName(this, session.displayName());
        if (TextUtils.isEmpty(display)) display = session.username();
        if (TextUtils.isEmpty(display)) display = "汐语";
        textHomeName.setText(display);
        avatarInitial.setText(display.substring(0, 1));
        String uri = ProfileStore.avatarUri(this);
        if (TextUtils.isEmpty(uri)) {
            avatarImage.setImageDrawable(null);
            avatarInitial.setVisibility(View.VISIBLE);
            return;
        }
        try {
            avatarImage.setImageURI(Uri.parse(uri));
        } catch (Exception e) {
            avatarImage.setImageDrawable(null);
        }
        avatarInitial.setVisibility(avatarImage.getDrawable() == null
                ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onStop() {
        TideService.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void loadConversations() {
        if (loading) return;
        loading = true;
        progress.setVisibility(View.VISIBLE);
        textHomeError.setVisibility(View.GONE);
        new Thread(() -> {
            try {
                JSONObject response = client.listConversations();
                JSONArray array = response.optJSONArray("conversations");
                Conversation direct = null;
                List<Conversation> groups = new ArrayList<>();
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject object = array.optJSONObject(i);
                        if (object == null) continue;
                        Conversation conversation = Conversation.fromJson(object);
                        if (conversation.isGroup()) {
                            groups.add(conversation);
                        } else if (direct == null) {
                            // 与 AI 只保留一个私聊，按服务端 updatedAt 顺序取最新一条。
                            direct = conversation;
                        }
                    }
                }
                Collections.sort(groups, (a, b) -> {
                    boolean pa = ChatPrefs.isPinned(this, a.id);
                    boolean pb = ChatPrefs.isPinned(this, b.id);
                    if (pa != pb) return pa ? -1 : 1;
                    return 0;
                });
                List<Conversation> items = new ArrayList<>();
                if (direct != null) items.add(direct);
                items.addAll(groups);
                main.post(() -> {
                    loading = false;
                    progress.setVisibility(View.GONE);
                    textHomeError.setVisibility(View.GONE);
                    adapter.setItems(items);
                    empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
                });
            } catch (Exception e) {
                main.post(() -> {
                    loading = false;
                    progress.setVisibility(View.GONE);
                    empty.setVisibility(View.GONE);
                    showError(getString(R.string.messages_load_failed));
                });
            }
        }, "tide-conversations").start();
    }

    private void showError(String message) {
        textHomeError.setText(message + " · " + getString(R.string.messages_retry));
        textHomeError.setVisibility(View.VISIBLE);
        textHomeError.setOnClickListener(v -> {
            textHomeError.setVisibility(View.GONE);
            loadConversations();
        });
    }

    private void updateConnection(int state) {
        if (textHomeConn == null) return;
        switch (state) {
            case TideService.STATE_ONLINE:
                textHomeConn.setText(R.string.messages_connected);
                break;
            case TideService.STATE_CONNECTING:
                textHomeConn.setText(R.string.messages_connecting);
                break;
            case TideService.STATE_FAILED:
                textHomeConn.setText(R.string.chat_failed_plain);
                break;
            case TideService.STATE_PROTOCOL:
                textHomeConn.setText(R.string.chat_protocol);
                break;
            case TideService.STATE_LOCKED:
                textHomeConn.setText(R.string.notif_locked);
                break;
            default:
                textHomeConn.setText(R.string.messages_offline);
                break;
        }
    }

    private void openProfile() {
        startActivity(new Intent(this, ProfileActivity.class));
    }

    private void openChat(String conversationId, String title) {
        if (TextUtils.isEmpty(conversationId)) return;
        session.setConversationId(conversationId);
        startActivity(new Intent(this, ChatActivity.class)
                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(ChatActivity.EXTRA_TITLE, title));
    }

    private void showAddSheet() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.sheet_add);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.38f);
            window.getDecorView().setPadding(dp(12), 0, dp(12), dp(12));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        LinearLayout items = dialog.findViewById(R.id.sheet_items);
        boolean admin = session.isAdmin();
        if (admin) {
            addSheetItem(dialog, items, R.drawable.ic_tab_chat, R.string.add_direct,
                    this::openDirectChat);
            addSheetItem(dialog, items, R.drawable.ic_group, R.string.add_group,
                    this::createGroup);
            addSheetItem(dialog, items, R.drawable.ic_link, R.string.add_join_group,
                    this::joinGroupByCode);
        } else {
            addSheetItem(dialog, items, R.drawable.ic_tab_chat, R.string.add_apply_chat,
                    this::applyForChat);
            addSheetItem(dialog, items, R.drawable.ic_link, R.string.add_join_group,
                    this::joinGroupByCode);
            addSheetItem(dialog, items, R.drawable.ic_group, R.string.add_browse_groups,
                    this::browseGroups);
            addSheetItem(dialog, items, R.drawable.ic_browse, R.string.add_my_requests,
                    this::showMyRequests);
        }
        if (items.getChildCount() > 0) {
            items.removeViewAt(items.getChildCount() - 1);
        }
    }

    private void addSheetItem(Dialog dialog, LinearLayout parent, int iconRes,
                              int labelRes, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        row.setPadding(dp(18), 0, dp(18), 0);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setClickable(true);
        row.setFocusable(true);

        FrameLayout iconBox = new FrameLayout(this);
        iconBox.setBackgroundResource(R.drawable.bg_add_icon);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(dp(34), dp(34));
        row.addView(iconBox, boxParams);
        ImageView icon = new ImageView(this);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(18), dp(18));
        iconParams.gravity = Gravity.CENTER;
        iconBox.addView(icon, iconParams);
        icon.setImageResource(iconRes);
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                getResources().getColor(R.color.teal_dark)));

        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextSize(15);
        label.setTextColor(getResources().getColor(R.color.ink));
        label.setPadding(dp(14), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(v -> {
            dialog.dismiss();
            action.run();
        });
        parent.addView(row);

        View line = new View(this);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        lineParams.leftMargin = dp(66);
        line.setBackgroundColor(getResources().getColor(R.color.line2));
        parent.addView(line, lineParams);
    }

    private void openDirectChat() {
        new Thread(() -> {
            try {
                JSONObject list = client.listConversations();
                JSONArray array = list.optJSONArray("conversations");
                String id = null;
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.optJSONObject(i);
                        if (item != null && "direct".equals(item.optString("kind", "direct"))) {
                            id = item.optString("id", null);
                            break;
                        }
                    }
                }
                if (TextUtils.isEmpty(id)) {
                    JSONObject created = client.createConversation("汐语");
                    id = created.optString("id", null);
                }
                final String conversationId = id;
                main.post(() -> {
                    if (TextUtils.isEmpty(conversationId)) {
                        showError("还没有可用的私聊");
                    } else {
                        openChat(conversationId, AiProfileStore.name(this, "汐语"));
                    }
                });
            } catch (Exception e) {
                main.post(() -> showError("打开私聊失败：" + message(e)));
            }
        }, "tide-direct").start();
    }

    private void applyForChat() {
        new Thread(() -> {
            try {
                client.createRequest("bot");
                main.post(() -> Toast.makeText(this,
                        "申请已提交，等管理员审批", Toast.LENGTH_LONG).show());
            } catch (TideClient.TideException e) {
                if ("already-has-chat".equals(e.code)) {
                    openDirectChat();
                } else {
                    main.post(() -> showError("申请失败：" + e.getMessage()));
                }
            } catch (Exception e) {
                main.post(() -> showError("申请失败：" + message(e)));
            }
        }, "tide-apply").start();
    }

    private void joinGroupByCode() {
        EditText input = new EditText(this);
        input.setHint(R.string.hint_group_invite);
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_join_group)
                .setView(input)
                .setPositiveButton("加入", (d, w) -> {
                    String code = input.getText().toString().trim();
                    if (!TextUtils.isEmpty(code)) acceptGroupCode(code);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void acceptGroupCode(String code) {
        new Thread(() -> {
            try {
                JSONObject response = client.acceptGroupInvite(code);
                String conversationId = response.optString("conversationId", "");
                main.post(() -> {
                    if (TextUtils.isEmpty(conversationId)) {
                        loadConversations();
                    } else {
                        openChat(conversationId, "群聊");
                    }
                });
            } catch (Exception e) {
                String text = e instanceof TideClient.TideException
                        && "already-member".equals(((TideClient.TideException) e).code)
                        ? getString(R.string.join_already_member)
                        : "入群失败：" + message(e);
                main.post(() -> showError(text));
            }
        }, "tide-join-code").start();
    }

    private void browseGroups() {
        new Thread(() -> {
            try {
                JSONObject response = client.listJoinableGroups();
                JSONArray array = response.optJSONArray("groups");
                if (array == null || array.length() == 0) {
                    main.post(() -> Toast.makeText(this,
                            "暂无可加入的群", Toast.LENGTH_SHORT).show());
                    return;
                }
                final JSONObject[] groups = new JSONObject[array.length()];
                final String[] labels = new String[array.length()];
                for (int i = 0; i < array.length(); i++) {
                    groups[i] = array.optJSONObject(i);
                    labels[i] = groups[i].optString("title", "群聊")
                            + " · " + groups[i].optInt("memberCount", 0) + " 人";
                }
                main.post(() -> new AlertDialog.Builder(this)
                        .setTitle(R.string.add_browse_groups)
                        .setItems(labels, (d, which) -> requestJoinGroup(groups[which]))
                        .setNegativeButton("关闭", null)
                        .show());
            } catch (Exception e) {
                main.post(() -> showError("群列表加载失败：" + message(e)));
            }
        }, "tide-groups").start();
    }

    private void requestJoinGroup(JSONObject group) {
        if (group == null) return;
        String id = group.optString("id", "");
        String title = group.optString("title", "群聊");
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("向管理员申请加入这个群？")
                .setPositiveButton("申请", (d, w) -> new Thread(() -> {
                    try {
                        client.createGroupRequest(id);
                        main.post(() -> Toast.makeText(this,
                                "申请已提交", Toast.LENGTH_SHORT).show());
                    } catch (Exception e) {
                        main.post(() -> showError("申请失败：" + message(e)));
                    }
                }, "tide-group-request").start())
                .setNegativeButton("取消", null)
                .show();
    }

    private void showMyRequests() {
        new Thread(() -> {
            try {
                JSONObject response = client.myRequests();
                JSONArray array = response.optJSONArray("requests");
                StringBuilder text = new StringBuilder();
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject item = array.optJSONObject(i);
                        if (item == null) continue;
                        text.append("· ").append(item.optString("status", "?"))
                                .append("（").append(item.optString("kind", "bot"))
                                .append("）\n");
                    }
                }
                String message = text.length() == 0 ? "还没有申请。" : text.toString();
                main.post(() -> new AlertDialog.Builder(this)
                        .setTitle(R.string.add_my_requests)
                        .setMessage(message)
                        .setPositiveButton("知道了", null)
                        .show());
            } catch (Exception e) {
                main.post(() -> showError("申请列表加载失败：" + message(e)));
            }
        }, "tide-requests").start();
    }

    private void createGroup() {
        EditText input = new EditText(this);
        input.setHint("群名称");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_group)
                .setView(input)
                .setPositiveButton("创建", (d, w) -> {
                    String title = input.getText().toString().trim();
                    if (TextUtils.isEmpty(title)) return;
                    new Thread(() -> {
                        try {
                            client.createGroup(title);
                            main.post(() -> {
                                Toast.makeText(this, "建群成功", Toast.LENGTH_SHORT).show();
                                loadConversations();
                            });
                        } catch (Exception e) {
                            main.post(() -> showError("建群失败：" + message(e)));
                        }
                    }, "tide-create-group").start();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void createInvite() {
        new Thread(() -> {
            try {
                JSONObject response = client.createInvite(1440, 1);
                JSONObject invite = response.optJSONObject("invite");
                main.post(() -> showInvite(invite));
            } catch (Exception e) {
                main.post(() -> showError("创建邀请失败：" + message(e)));
            }
        }, "tide-create-invite").start();
    }

    private void showInvite(JSONObject invite) {
        String code = invite == null ? "" : invite.optString("code", "");
        String joinUrl = invite == null ? "" : invite.optString("joinUrl", "");
        String message = (joinUrl.isEmpty() ? "邀请码：" + code : joinUrl + "\n\n邀请码：" + code)
                + "\n\n24 小时内有效，默认只能用 1 次。";
        TextView text = new TextView(this);
        text.setText(message);
        text.setTextIsSelectable(true);
        text.setTextSize(14);
        text.setPadding(dp(20), dp(8), dp(20), dp(8));
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.add_invite)
                .setView(text)
                .setPositiveButton("复制", (d, w) -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                                "邀请", joinUrl.isEmpty() ? code : joinUrl));
                    }
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("知道了", null);
        if (!joinUrl.isEmpty()) {
            builder.setNeutralButton("分享", (d, w) -> {
                Intent share = new Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, message);
                startActivity(Intent.createChooser(share, "分享邀请"));
            });
        }
        builder.show();
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onReady(long latestSeq) {
    }

    @Override
    public void onMessage(Message message) {
    }

    @Override
    public void onTurn(boolean start, String reason) {
    }

    @Override
    public void onUnreadChanged(String conversationId) {
        main.removeCallbacks(refreshUnread);
        main.postDelayed(refreshUnread, 800);
    }

    @Override
    public void onHistoryTruncated(String conversationId, long latestSeq, int loadedMessages) {
    }

    @Override
    public void onState(int state) {
        main.post(() -> updateConnection(state));
    }
}
