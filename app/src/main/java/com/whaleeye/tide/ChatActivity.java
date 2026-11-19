package com.whaleeye.tide;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.AbsListView;
import android.widget.ImageButton;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 聊天页。只管三件事：拉历史、发消息、把 UI 挂到 {@link TideService} 上。
 *
 * SSE 连接不在这里——它归前台服务，所以退到后台、锁屏、切 App 都不影响接收。
 * 页面进出只加减监听器，不动连接。
 */
public final class ChatActivity extends Activity implements TideService.Listener {

    private static final int REQ_NOTIFICATION = 0x7101;
    private static final int REQ_PICK_IMAGE = 0x7102;
    private static final int REQ_PICK_FILE = 0x7103;
    private static final int REQ_SEARCH = 0x7104;
    private static final int REQ_AUDIO = 0x7105;
    private static final long MAX_VOICE_MS = 60_000L;
    private static final int MAX_RELOAD_PAGES = 20;
    public static final String EXTRA_CONVERSATION_ID = "conversation_id";
    public static final String EXTRA_TITLE = "conversation_title";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService readExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "tide-read");
        thread.setDaemon(true);
        return thread;
    });
    private long pendingReadSeq = 0;
    private long confirmedReadSeq = 0;
    private boolean readFlushPosted = false;
    private boolean readInFlight = false;
    private boolean reloadInFlight = false;
    private boolean botProfileLoading = false;
    private volatile long lastProfileRefreshAt = 0L;
    private long readGuardSeq = Long.MAX_VALUE;
    private long reloadCursor = 0;
    private long oldestLoadedSeq = 0;
    private boolean historyHasMore = false;
    private boolean userScrolledMessages = false;

    private Session session;
    private TideClient client;
    private MessageAdapter adapter;
    private ListView listMessages;
    private EditText inputMessage;
    private ImageButton voiceButton;
    private ImageButton attachButton;
    private LinearLayout attachPanel;
    private ImageButton btnSend;
    private LinearLayout stickerPanel;
    private GridView stickerGrid;
    private TextView stickerEmpty;
    private TextView stickerTabRecent;
    private TextView stickerTabAll;
    private StickerAdapter stickerAdapter;
    private final List<Sticker> stickers = new ArrayList<>();
    private boolean stickerRecentOnly = true;
    private boolean stickerLoading;
    private TextView textConn;
    private TextView textTitle;
    private TextView textAnnouncement;
    private View dividerAnnouncement;
    private LinearLayout replyBar;
    private TextView replyTitle;
    private TextView replyText;
    private boolean canSend;
    private Message pendingReply;
    private JSONArray mentionMembers;
    private long mentionLoadedAt;
    private PopupWindow mentionPopup;
    private boolean suppressMentionTrigger;
    private volatile String currentAnnouncement;
    private volatile String currentRole = "member";
    private boolean announcementExpanded;
    private final Runnable typingWatchdog = () -> {
        if (!isFinishing() && textConn != null) {
            textConn.setText(getString(R.string.chat_online));
        }
    };

    private Runnable flushRead;
    private MediaRecorder voiceRecorder;
    private File voiceFile;
    private long voiceStartedAt;
    private float voiceTouchY;
    private boolean voiceCancel;
    private PopupWindow voicePopup;
    private TextView voicePopupText;

    private final Runnable voiceMax = () -> finishVoiceRecording(false);
    private final Runnable voiceOverlayTick = new Runnable() {
        @Override
        public void run() {
            if (voiceRecorder == null) return;
            long elapsed = System.currentTimeMillis() - voiceStartedAt;
            int seconds = (int) Math.max(0, elapsed / 1000);
            if (voicePopupText != null) {
                voicePopupText.setText((voiceCancel
                        ? getString(R.string.voice_release_cancel)
                        : getString(R.string.voice_recording))
                        + "\n" + String.format(java.util.Locale.CHINA, "%d:%02d", seconds / 60, seconds % 60));
            }
            main.postDelayed(this, 200L);
        }
    };

    private void flushReadNow() {
        readFlushPosted = false;
        if (readInFlight || leaving || conversationId == null) return;
        long target = pendingReadSeq;
        if (target <= confirmedReadSeq) return;
        String cid = conversationId;
        readInFlight = true;
        readExecutor.execute(() -> {
            try {
                client.postRead(cid, target);
                main.post(() -> {
                    readInFlight = false;
                    confirmedReadSeq = Math.max(confirmedReadSeq, target);
                    if (pendingReadSeq > target && !leaving) scheduleReadFlush();
                });
            } catch (Exception ignored) {
                // 不弹错：下次打开聊天或收到新消息会按同一水位重试。
                main.post(() -> readInFlight = false);
            }
        });
    }

    private volatile String conversationId;
    private String requestedConversationId;
    private String requestedTitle;
    private volatile String currentPolicy = "restricted";
    private volatile String currentKind = "direct";

    /** 最近一次连接状态，决定状态栏能不能点（只有 FAILED 时点得动）。 */
    private int lastState = TideService.STATE_OFFLINE;

    /** 已经决定离开页面（重新登录或退出登录），后台线程不要再回写 UI。 */
    private volatile boolean leaving = false;

    /**
     * bootstrap 已判定登录态死掉或协议不匹配。置位后任何「状态刷新」都不许覆盖文案，
     * 否则切一次后台再回来，onStart/onResume 会拿 Service 的 OFFLINE 把「登录已失效」
     * 换成「已断开」并把点击入口换成检查 lastState 的闭包 —— 处理办法就永久消失了。
     */
    private volatile boolean authDead = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.apply(this);
        setContentView(R.layout.activity_chat);
        flushRead = this::flushReadNow;

        session = new Session(this);
        client = new TideClient(session);
        adapter = new MessageAdapter(this, client, session.displayName());
        requestedConversationId = getIntent().getStringExtra(EXTRA_CONVERSATION_ID);
        requestedTitle = getIntent().getStringExtra(EXTRA_TITLE);

        listMessages = findViewById(R.id.list_messages);
        inputMessage = findViewById(R.id.input_message);
        voiceButton = findViewById(R.id.btn_voice);
        attachButton = findViewById(R.id.btn_attach);
        attachPanel = findViewById(R.id.attach_panel);
        stickerPanel = findViewById(R.id.sticker_panel);
        stickerGrid = findViewById(R.id.sticker_grid);
        stickerEmpty = findViewById(R.id.sticker_empty);
        stickerTabRecent = findViewById(R.id.sticker_tab_recent);
        stickerTabAll = findViewById(R.id.sticker_tab_all);
        stickerAdapter = new StickerAdapter(this, client, stickerCellSize());
        stickerGrid.setAdapter(stickerAdapter);
        stickerGrid.setOnItemClickListener((parent, view, position, id) -> {
            Sticker sticker = stickerAdapter.getItem(position);
            if (sticker != null) sendSticker(sticker);
        });
        textConn = findViewById(R.id.text_conn);
        textTitle = findViewById(R.id.text_title);
        textAnnouncement = findViewById(R.id.text_announcement);
        dividerAnnouncement = findViewById(R.id.divider_announcement);
        replyBar = findViewById(R.id.reply_bar);
        replyTitle = findViewById(R.id.reply_title);
        replyText = findViewById(R.id.reply_text);
        btnSend = findViewById(R.id.btn_send);

        View messageFooter = new View(this);
        messageFooter.setLayoutParams(new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(18)));
        listMessages.addFooterView(messageFooter, null, false);
        listMessages.setAdapter(adapter);
        listMessages.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL
                        || scrollState == SCROLL_STATE_FLING) {
                    userScrolledMessages = true;
                }
                if (scrollState == SCROLL_STATE_IDLE && userScrolledMessages
                        && view.getLastVisiblePosition() >= adapter.getCount() - 1) {
                    adapter.clearUnreadDivider();
                    userScrolledMessages = false;
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem,
                                 int visibleItemCount, int totalItemCount) {
            }
        });
        adapter.setOnImageClickListener(this::showImagePreview);
        adapter.setOnFileClickListener(this::openFile);
        adapter.setOnMessageLongClickListener(this::showMessageMenu);
        adapter.setOnQuoteClickListener(this::jumpToQuote);
        adapter.setOnAvatarClickListener(this::openProfileFor);
        adapter.setSelfUserId(session.userId());
        textTitle.setText(TextUtils.isEmpty(requestedTitle)
                ? AiProfileStore.name(this, "汐语") : requestedTitle);
        textTitle.setOnClickListener(v -> openChatInfo());

        btnSend.setOnClickListener(v -> sendMessage());
        findViewById(R.id.btn_reply_close).setOnClickListener(v -> clearReply());
        findViewById(R.id.btn_search).setOnClickListener(v -> openSearch());
        textAnnouncement.setOnClickListener(v -> toggleAnnouncement());
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_more).setOnClickListener(v -> openChatInfo());
        voiceButton.setOnTouchListener((v, event) -> handleVoiceTouch(event));
        attachButton.setOnClickListener(v -> toggleAttachPanel());
        attachPanel.findViewById(R.id.attach_sticker)
                .setOnClickListener(v -> {
                    hideAttachPanel();
                    showStickerPanel();
                });
        attachPanel.findViewById(R.id.attach_image)
                .setOnClickListener(v -> {
                    hideAttachPanel();
                    pickImage();
                });
        attachPanel.findViewById(R.id.attach_file)
                .setOnClickListener(v -> {
                    hideAttachPanel();
                    pickFile();
                });
        findViewById(R.id.btn_sticker_close).setOnClickListener(v -> hideStickerPanel());
        stickerTabRecent.setOnClickListener(v -> selectStickerTab(true));
        stickerTabAll.setOnClickListener(v -> selectStickerTab(false));
        inputMessage.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                hideStickerPanel();
                hideAttachPanel();
            }
        });
        View contentRoot = findViewById(android.R.id.content);
        if (contentRoot != null) {
            contentRoot.setOnApplyWindowInsetsListener((v, insets) -> {
                if (insets.getSystemWindowInsetBottom() > dp(120)) {
                    hideStickerPanel();
                    hideAttachPanel();
                }
                return insets;
            });
        }
        inputMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (count <= before || suppressMentionTrigger
                        || !"group".equals(currentKind)) return;
                int from = Math.max(0, start);
                int to = Math.min(s.length(), start + count);
                if (from >= to) return;
                String inserted = s.subSequence(from, to).toString();
                int at = Math.max(inserted.lastIndexOf('@'), inserted.lastIndexOf('＠'));
                if (at >= 0) showMentionPicker(from + at);
            }
            @Override public void afterTextChanged(Editable s) {
                updateSendButton();
            }
        });
        setComposerEnabled(false);
        inputMessage.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = actionId == EditorInfo.IME_ACTION_SEND
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN && !event.isShiftPressed());
            if (enter) {
                sendMessage();
                return true;
            }
            return false;
        });

        if (!session.hasLogin()) {
            Toast.makeText(this, "登录状态丢了，请重新登录", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        askNotificationPermission();
        bootstrap();
    }

    /** 找会话 → 拉历史 → 起服务。会话 id 落盘后，服务重启时能自己接上。 */
    private void bootstrap() {
        new Thread(() -> {
            try {
                final boolean explicitConversation = !TextUtils.isEmpty(requestedConversationId);
                String cid = explicitConversation
                        ? requestedConversationId
                        : session.conversationId();
                String title = TextUtils.isEmpty(requestedTitle)
                        ? AiProfileStore.name(this, "汐语") : requestedTitle;
                JSONObject list = null;
                try {
                    list = client.listConversations();
                } catch (Exception e) {
                    // 列表拉不到时，已有 conversationId 还能继续用；没有就必须失败。
                    if (cid == null || cid.isEmpty()) throw e;
                }
                if (list != null) {
                    JSONArray items = list.optJSONArray("conversations");
                    if (items == null) items = list.optJSONArray("items");
                    if (explicitConversation) {
                        boolean found = false;
                        if (items != null) {
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.optJSONObject(i);
                                if (item != null && cid.equals(item.optString("id", ""))) {
                                    found = true;
                                    currentPolicy = item.optString("policy", "restricted");
                                    currentKind = item.optString("kind", "direct");
                                    currentAnnouncement = announcementOf(item);
                                    currentRole = item.optString("role", "member");
                                    title = "group".equals(item.optString("kind", "direct"))
                                            ? item.optString("title", title)
                                            : "汐语";
                                    break;
                                }
                            }
                        }
                        if (!found) {
                            throw new TideClient.TideException(
                                    404,
                                    "conversation-not-found",
                                    "这个会话不存在或你已不在其中"
                            );
                        }
                    } else {
                        // 默认入口仍是 1:1 私聊；只有显式点“我的群聊”才进群。
                        String directId = null;
                        Boolean storedIsDirect = null;
                        if (items != null) {
                            for (int i = 0; i < items.length(); i++) {
                                JSONObject item = items.optJSONObject(i);
                                if (item == null) continue;
                                String id = item.optString("id", null);
                                String kind = item.optString("kind", "direct");
                                if ("direct".equals(kind) && directId == null) {
                                    directId = id;
                                    title = "汐语";
                                }
                                if (cid != null && cid.equals(id)) {
                                    storedIsDirect = "direct".equals(kind);
                                    currentPolicy = item.optString("policy", "restricted");
                                    currentKind = kind;
                                    currentAnnouncement = announcementOf(item);
                                    currentRole = item.optString("role", "member");
                                }
                            }
                        }
                        if (cid == null || cid.isEmpty() || !Boolean.TRUE.equals(storedIsDirect)) {
                            cid = directId;
                        }
                    }
                }
                if (cid == null || cid.isEmpty()) {
                    JSONObject created = client.createConversation("汐语");
                    cid = created.optString("id", null);
                }
                if (cid == null || cid.isEmpty()) {
                    throw new TideClient.TideException(400, "protocol-error", "拿不到会话 id");
                }

                final String conversation = cid;
                final String conversationTitle = title;
                long loadedLastRead = 0L;
                if (list != null) {
                    JSONArray readItems = list.optJSONArray("conversations");
                    if (readItems == null) readItems = list.optJSONArray("items");
                    if (readItems != null) {
                        for (int i = 0; i < readItems.length(); i++) {
                            JSONObject item = readItems.optJSONObject(i);
                            if (item != null && cid.equals(item.optString("id", ""))) {
                                loadedLastRead = item.optLong("last_read_seq", 0L);
                                break;
                            }
                        }
                    }
                }
                final long initialLastReadSeq = loadedLastRead;
                session.setConversationId(conversation);
                conversationId = conversation;

                JSONObject page = client.listMessages(conversation, 0, 50);
                JSONArray arr = TideClient.messagesOf(page);
                final boolean initialHasMore = page.optBoolean("has_more", false);
                List<Message> history = new ArrayList<>();
                long oldestHistory = Long.MAX_VALUE;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        Message message = Message.fromJson(o);
                        history.add(message);
                        if (message.seq > 0) oldestHistory = Math.min(oldestHistory, message.seq);
                    }
                }
                final long oldestInitial = oldestHistory == Long.MAX_VALUE ? 0 : oldestHistory;
                main.post(() -> {
                    if (leaving) return;
                    textTitle.setText(conversationTitle);
                    applyAnnouncement(currentAnnouncement);
                    adapter.addAll(history);
                    long dividerReadSeq = Math.max(
                            initialLastReadSeq,
                            TideService.currentLastReadSeq(conversation));
                    adapter.showUnreadDividerAfter(dividerReadSeq);
                    int dividerPosition = adapter.positionOfUnreadDivider();
                    if (dividerPosition >= 0) scrollToPosition(dividerPosition);
                    else scrollToBottom();
                    oldestLoadedSeq = oldestInitial;
                    historyHasMore = initialHasMore;
                    reloadCursor = initialHasMore ? oldestInitial : 0;
                    readGuardSeq = initialHasMore
                            ? Math.max(0, oldestInitial - 1)
                            : adapter.lastSeq();
                    reloadIfBehind();
                    attachService();
                });
            } catch (Exception e) {
                if (isAuthFailure(e)) {
                    showAuthFailure();
                    return;
                }
                if (isProtocolFailure(e)) {
                    showProtocolFailure();
                    return;
                }
                String msg = friendlyError(e);
                main.post(() -> {
                    if (leaving) return;
                    textConn.setText(msg);
                    textConn.setClickable(false);
                    Toast.makeText(this, "初始化失败：" + msg, Toast.LENGTH_LONG).show();
                });
            }
        }, "tide-bootstrap").start();
    }

    /** 挂上服务：起连接、加监听、清未读。 */
    private void attachService() {
        TideService.startConversation(this, conversationId, textTitle.getText().toString());
        TideService.addListener(conversationId, this);
        TideService.clearUnread(conversationId);
        markReadThrough(Math.max(adapter.lastSeq(), TideService.currentLastSeq(conversationId)));
        lastState = TideService.currentState();
        textConn.setText(stateText(lastState));
        textConn.setClickable(lastState == TideService.STATE_FAILED);
        textConn.setOnClickListener(v -> {
            if (lastState == TideService.STATE_FAILED) relogin();
        });
        setComposerEnabled(lastState == TideService.STATE_ONLINE);
    }

    // ── TideService.Listener（都在 SSE 线程回调，必须回主线程） ────────────

    @Override
    public void onReady(long latestSeq) {
        main.post(() -> {
            main.removeCallbacks(typingWatchdog);
            textConn.setText(R.string.chat_online);
            setComposerEnabled(true);
            reloadIfBehind(latestSeq);
            markReadThrough(Math.max(latestSeq, adapter.lastSeq()));
        });
    }

    @Override
    public void onMessage(Message m) {
        main.post(() -> {
            if (m.isUser()) {
                if (m.clientMsgId != null && !m.clientMsgId.isEmpty()) {
                    adapter.confirmPending(m.clientMsgId, m);
                } else {
                    // 老网关没有 clientMsgId：按同一条用户文本替换 pending。
                    adapter.confirmPendingByText(m);
                }
            } else if (!adapter.containsSeq(m.seq)) {
                adapter.add(m);
            }
            if (!m.isUser()) {
                // 模型已经发出一条正文，就不该再显示“正在输入”；chat_wait 期间很可能是长等待。
                main.removeCallbacks(typingWatchdog);
                textConn.setText(R.string.chat_online);
                long now = System.currentTimeMillis();
                if (now - lastProfileRefreshAt > 30_000L) {
                    lastProfileRefreshAt = now;
                    refreshBotProfile();
                }
            }
            markReadThrough(m.seq);
            scrollToBottom();
        });
    }

    @Override
    public void onTurn(boolean start, String reason) {
        main.post(() -> {
            main.removeCallbacks(typingWatchdog);
            if (start) {
                textConn.setText("对方正在输入…");
                // turn 里可能包含 chat_wait 长等待；60 秒没有正文就把提示收掉。
                main.postDelayed(typingWatchdog, 60_000);
            } else {
                textConn.setText(R.string.chat_online);
            }
        });
    }

    @Override
    public void onHistoryTruncated(String conversationId, long latestSeq, int loadedMessages) {
        main.post(() -> {
            if (conversationId != null && conversationId.equals(this.conversationId)) {
                textConn.setText("历史消息过多，已跳到最新");
            }
        });
    }

    @Override
    public void onEvent(String cid, String type, JSONObject event) {
        main.post(() -> {
            if (leaving || cid == null || !cid.equals(conversationId)) return;
            if ("conversation.updated".equals(type)) {
                String title = event.optString("title", null);
                if (title != null && !title.isEmpty()) textTitle.setText(title);
                if (event.has("announcement")) {
                    applyAnnouncement(event.isNull("announcement")
                            ? null : event.optString("announcement", null));
                }
            } else if ("message.recalled".equals(type)) {
                adapter.markRecalled(event.optString("messageId", ""));
            } else if ("conversation.deleted".equals(type)) {
                Toast.makeText(this, "会话已关闭", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
    }

    @Override
    public void onUnreadChanged(String conversationId) {
    }

    @Override
    public void onState(int state) {
        main.post(() -> {
            // 已判定登录态死掉时，只放行两类终态；其它（含 Service 重启带来的 OFFLINE）
            // 一律不刷，保住状态栏上的重新登录入口。
            if (authDead && state != TideService.STATE_FAILED && state != TideService.STATE_PROTOCOL) {
                return;
            }
            lastState = state;
            textConn.setText(stateText(state));
            // 登录态没救时，状态栏是可以点的 —— 否则用户只能杀进程重装
            textConn.setClickable(state == TideService.STATE_FAILED);
            setComposerEnabled(state == TideService.STATE_ONLINE);
        });
    }

    /** 登录态失效后唯一的出路：清凭据、停常驻服务、回登录页。 */
    private void relogin() {
        if (leaving) return;
        leaving = true;
        TideService.stop(this);
        LockManager.lockAndWipe();
        new Session(this).clearLogin();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    /** bootstrap 阶段的鉴权失败：不依赖 Service，直接把状态栏变成重新登录入口。 */
    private void showAuthFailure() {
        main.post(() -> {
            if (leaving) return;
            authDead = true;
            lastState = TideService.STATE_FAILED;
            textConn.setText(R.string.chat_failed);
            textConn.setClickable(true);
            textConn.setOnClickListener(v -> relogin());
            setComposerEnabled(false);
        });
    }

    /** bootstrap 阶段的协议不匹配：终态提示，但不给重新登录入口。 */
    private void showProtocolFailure() {
        main.post(() -> {
            if (leaving) return;
            authDead = true;
            lastState = TideService.STATE_PROTOCOL;
            textConn.setText(R.string.chat_protocol);
            textConn.setClickable(false);
            setComposerEnabled(false);
        });
    }

    private boolean isAuthFailure(Exception e) {
        if (e instanceof TideClient.TideException) {
            TideClient.TideException te = (TideClient.TideException) e;
            return te.status == 401 || "refresh-auth-failed".equals(te.code);
        }
        return false;
    }

    private boolean isProtocolFailure(Exception e) {
        if (e instanceof TideClient.TideException) {
            String code = ((TideClient.TideException) e).code;
            return "refresh-protocol".equals(code) || "protocol-error".equals(code);
        }
        return false;
    }

    private void showSessionMenu() {
        boolean admin = session != null && session.isAdmin();
        if (!admin) {
            confirmLogout();
            return;
        }
        String policyLabel = "owner".equals(currentPolicy)
                ? getString(R.string.policy_switch_restricted)
                : getString(R.string.policy_switch_owner);
        new AlertDialog.Builder(this)
                .setTitle(textTitle.getText())
                .setItems(new String[]{policyLabel, getString(R.string.action_logout)},
                        (dialog, which) -> {
                            if (which == 0) {
                                if (LockManager.isUnlocked()) {
                                    confirmPolicySwitch("owner".equals(currentPolicy)
                                            ? "restricted" : "owner");
                                } else {
                                    TideApp.ensureUnlocked(this);
                                }
                            } else {
                                confirmLogout();
                            }
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void openChatInfo() {
        if (conversationId == null || conversationId.isEmpty()) return;
        String title = textTitle.getText().toString();
        if ("group".equals(currentKind)) {
            startActivity(new Intent(this, GroupInfoActivity.class)
                    .putExtra(GroupInfoActivity.EXTRA_CONVERSATION_ID, conversationId)
                    .putExtra(GroupInfoActivity.EXTRA_TITLE, title));
        } else {
            startActivity(new Intent(this, ChatInfoActivity.class)
                    .putExtra(ChatInfoActivity.EXTRA_CONVERSATION_ID, conversationId)
                    .putExtra(ChatInfoActivity.EXTRA_TITLE, title)
                    .putExtra(ChatInfoActivity.EXTRA_POLICY, currentPolicy));
        }
    }

    private void confirmPolicySwitch(String next) {
        if (!LockManager.isUnlocked()) {
            // 本地门禁：未解锁不读取 token、不发切换请求；服务端另有 owner_ids 校验。
            TideApp.ensureUnlocked(this);
            return;
        }
        if (conversationId == null || conversationId.isEmpty()) {
            Toast.makeText(this, "会话还没准备好", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("owner".equals(next)
                        ? R.string.policy_switch_owner : R.string.policy_switch_restricted)
                .setMessage(R.string.policy_confirm)
                .setPositiveButton(R.string.policy_switch_action, (d, w) -> switchPolicy(next))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showMessageMenu(Message message, View anchor) {
        if (message == null || leaving || isFinishing()) return;
        View content = getLayoutInflater().inflate(R.layout.menu_message, null);
        PopupWindow popup = new PopupWindow(
                content, dp(112), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);

        TextView copy = content.findViewById(R.id.menu_copy);
        TextView resend = content.findViewById(R.id.menu_resend);
        TextView delete = content.findViewById(R.id.menu_delete);
        TextView reply = content.findViewById(R.id.menu_reply);
        TextView recall = content.findViewById(R.id.menu_recall);
        copy.setVisibility(TextUtils.isEmpty(message.text) ? View.GONE : View.VISIBLE);
        resend.setVisibility(message.isUser() && !message.recalled
                && (!TextUtils.isEmpty(message.text) || !message.attachments.isEmpty())
                ? View.VISIBLE : View.GONE);
        reply.setVisibility(message.recalled || message.pending ? View.GONE : View.VISIBLE);
        recall.setVisibility(canRecall(message) ? View.VISIBLE : View.GONE);

        reply.setOnClickListener(v -> {
            popup.dismiss();
            startReply(message);
        });
        recall.setOnClickListener(v -> {
            popup.dismiss();
            recallMessage(message);
        });

        copy.setOnClickListener(v -> {
            popup.dismiss();
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && !TextUtils.isEmpty(message.text)) {
                clipboard.setPrimaryClip(ClipData.newPlainText("消息", message.text));
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
            }
        });
        resend.setOnClickListener(v -> {
            popup.dismiss();
            adapter.remove(message);
            if (message.attachments.isEmpty()) sendText(message.text);
            else resendAttachments(message);
        });
        delete.setOnClickListener(v -> {
            popup.dismiss();
            adapter.remove(message);
        });
        int width = dp(112);
        content.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = content.getMeasuredHeight();
        int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int x = Math.max(dp(8), Math.min(
                location[0] + anchor.getWidth() - width,
                metrics.widthPixels - width - dp(8)));
        int y = location[1] + anchor.getHeight() + dp(4);
        if (y + height > metrics.heightPixels - dp(8)) {
            y = Math.max(dp(8), location[1] - height - dp(4));
        }
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
    }

    private void startReply(Message message) {
        pendingReply = message;
        String who = message.isUser()
                ? (TextUtils.isEmpty(message.senderName) ? "对方" : message.senderName)
                : AiProfileStore.name(this, "汐语");
        replyTitle.setText(getString(R.string.chat_reply_to, who));
        replyText.setText(snippetOf(message));
        replyBar.setVisibility(View.VISIBLE);
        inputMessage.requestFocus();
    }

    private void openProfileFor(Message message) {
        if (message == null) return;
        if (!message.isUser()) {
            startActivity(new Intent(this, AiProfileActivity.class)
                    .putExtra(AiProfileActivity.EXTRA_CONVERSATION_ID, conversationId)
                    .putExtra(AiProfileActivity.EXTRA_TITLE, textTitle.getText().toString()));
            return;
        }
        String selfId = session.userId();
        if (TextUtils.isEmpty(message.senderUserId) || message.senderUserId.equals(selfId)) {
            startActivity(new Intent(this, ProfileActivity.class));
            return;
        }
        startActivity(new Intent(this, MemberProfileActivity.class)
                .putExtra(MemberProfileActivity.EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(MemberProfileActivity.EXTRA_USER_ID, message.senderUserId)
                .putExtra(MemberProfileActivity.EXTRA_NAME, message.senderName)
                .putExtra(MemberProfileActivity.EXTRA_GROUP_TITLE,
                        textTitle.getText().toString()));
    }

    private void clearReply() {
        pendingReply = null;
        if (replyBar != null) replyBar.setVisibility(View.GONE);
    }

    private String snippetOf(Message message) {
        if (message.recalled) return getString(R.string.message_recalled);
        if (message.isSticker()) return "[表情]";
        if (message.voiceDurationMs > 0) return "[语音]";
        if (!TextUtils.isEmpty(message.text)) {
            return message.text.length() > 40 ? message.text.substring(0, 40) + "…" : message.text;
        }
        if (!message.attachments.isEmpty()) return "[附件]";
        return "…";
    }

    private boolean canRecall(Message message) {
        if (!message.isUser() || message.pending || message.recalled) return false;
        if ("group".equals(currentKind) && "owner".equals(currentRole)) return true;
        return message.senderUserId != null
                && message.senderUserId.equals(session.userId())
                && System.currentTimeMillis() - message.createdAt <= 120_000L;
    }

    private void recallMessage(Message message) {
        String cid = conversationId;
        if (cid == null) return;
        new Thread(() -> {
            try {
                client.recallMessage(cid, message.id);
                main.post(() -> adapter.markRecalled(message.id));
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this,
                        getString(R.string.chat_recall_failed) + "：" + friendlyError(e),
                        Toast.LENGTH_LONG).show());
            }
        }, "tide-recall").start();
    }

    private void jumpToQuote(Message message) {
        if (message == null || TextUtils.isEmpty(message.replyToId)) return;
        jumpToMessageId(message.replyToId);
    }

    private void jumpToMessageId(String messageId) {
        int position = adapter.positionOfMessageId(messageId);
        if (position >= 0) {
            listMessages.smoothScrollToPosition(position);
            return;
        }
        String cid = conversationId;
        if (cid == null) return;
        Toast.makeText(this, "正在定位…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                JSONObject page = client.messagesAround(cid, messageId, 50);
                List<Message> items = parseMessages(TideClient.messagesOf(page));
                boolean hasMore = page.optBoolean("has_more", false);
                main.post(() -> {
                    if (leaving || !cid.equals(conversationId) || items.isEmpty()) return;
                    adapter.setAll(items);
                    oldestLoadedSeq = items.get(0).seq;
                    reloadCursor = oldestLoadedSeq;
                    historyHasMore = hasMore;
                    int target = adapter.positionOfMessageId(messageId);
                    if (target >= 0) scrollToPosition(Math.max(0, target - 1));
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this,
                        "原消息拉不回来了：" + friendlyError(e), Toast.LENGTH_LONG).show());
            }
        }, "tide-jump").start();
    }

    private List<Message> parseMessages(JSONArray array) {
        List<Message> items = new ArrayList<>();
        if (array == null) return items;
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object != null) items.add(Message.fromJson(object));
        }
        return items;
    }

    private void openSearch() {
        String cid = conversationId;
        if (cid == null) return;
        startActivityForResult(new Intent(this, SearchActivity.class)
                .putExtra(SearchActivity.EXTRA_CONVERSATION_ID, cid), REQ_SEARCH);
    }

    private void applyAnnouncement(String text) {
        currentAnnouncement = text == null ? "" : text.trim();
        boolean show = !currentAnnouncement.isEmpty() && "group".equals(currentKind);
        textAnnouncement.setVisibility(show ? View.VISIBLE : View.GONE);
        dividerAnnouncement.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        textAnnouncement.setText(getString(R.string.chat_announcement)
                + "：" + currentAnnouncement);
        textAnnouncement.setMaxLines(announcementExpanded ? 6 : 1);
        textAnnouncement.setEllipsize(announcementExpanded ? null : TextUtils.TruncateAt.END);
    }

    private static String announcementOf(JSONObject item) {
        if (item == null || item.isNull("announcement")) return null;
        String value = item.optString("announcement", "");
        return value.isEmpty() || "null".equals(value) ? null : value;
    }

    private void toggleAnnouncement() {
        if (TextUtils.isEmpty(currentAnnouncement)) return;
        announcementExpanded = !announcementExpanded;
        applyAnnouncement(currentAnnouncement);
    }

    private void showMentionPicker(int at) {
        if (conversationId == null) return;
        new Thread(() -> {
            try {
                JSONArray members;
                synchronized (this) {
                    members = mentionMembers;
                }
                if (members == null || System.currentTimeMillis() - mentionLoadedAt > 60_000L) {
                    members = client.listMembers(conversationId).optJSONArray("members");
                    synchronized (this) {
                        mentionMembers = members;
                        mentionLoadedAt = System.currentTimeMillis();
                    }
                }
                final JSONArray list = members == null ? new JSONArray() : members;
                main.post(() -> showMentionPopup(at, list));
            } catch (Exception e) {
                android.util.Log.w("TideMention", "load failed", e);
            }
        }, "tide-mention").start();
    }

    private void showMentionPopup(int at, JSONArray members) {
        if (isFinishing() || inputMessage == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_menu_translucent);
        box.setPadding(0, dp(6), 0, dp(6));
        int shown = 0;
        for (int i = 0; i < members.length() && shown < 20; i++) {
            JSONObject member = members.optJSONObject(i);
            if (member == null) continue;
            String name = member.optString("display_name", "");
            if (TextUtils.isEmpty(name)) continue;
            TextView row = new TextView(this);
            row.setText(name);
            row.setTextSize(15);
            row.setTextColor(getResources().getColor(R.color.ink));
            row.setAlpha(0.78f);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
            row.setOnClickListener(v -> insertMention(at, name));
            box.addView(row);
            shown++;
        }
        if (shown == 0) return;
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        int width = dp(220);
        scroll.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = Math.min(scroll.getMeasuredHeight(), dp(300));
        PopupWindow popup = new PopupWindow(scroll, width, height, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        mentionPopup = popup;
        popup.showAsDropDown(inputMessage, 0,
                -(inputMessage.getHeight() + height + dp(8)));
    }

    private void insertMention(int at, String name) {
        if (mentionPopup != null) {
            mentionPopup.dismiss();
            mentionPopup = null;
        }
        Editable editable = inputMessage.getText();
        int cursor = Math.max(at + 1, inputMessage.getSelectionStart());
        suppressMentionTrigger = true;
        try {
            editable.replace(Math.max(0, at), Math.min(cursor, editable.length()),
                    "@" + name + " ");
        } finally {
            suppressMentionTrigger = false;
        }
        inputMessage.setSelection(Math.min(editable.length(), at + name.length() + 2));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void switchPolicy(String next) {
        final String cid = conversationId;
        final String title = textTitle.getText().toString();
        Toast.makeText(this, "正在切换权限…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                client.setPolicy(cid, next);
                currentPolicy = next;
                main.post(() -> {
                    Toast.makeText(this, getString(R.string.policy_switched, next),
                            Toast.LENGTH_SHORT).show();
                    TideService.startConversation(this, cid, title);
                });
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this,
                        getString(R.string.policy_failed, friendlyError(e)),
                        Toast.LENGTH_LONG).show());
            }
        }, "tide-policy").start();
    }

    /** 长按标题的永久退出入口：token 出任何问题都不需要去系统设置清数据。 */
    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_logout)
                .setMessage(R.string.logout_confirm)
                .setPositiveButton(R.string.action_logout_confirm, (d, w) -> relogin())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private String stateText(int state) {
        switch (state) {
            case TideService.STATE_ONLINE:
                return getString(R.string.chat_online);
            case TideService.STATE_CONNECTING:
                return getString(R.string.chat_connecting);
            case TideService.STATE_FAILED:
                return getString(R.string.chat_failed);
            case TideService.STATE_PROTOCOL:
                // 协议不匹配重试一万次也一样，但「重新登录」治不了它
                return getString(R.string.chat_protocol);
            case TideService.STATE_LOCKED:
                return getString(R.string.notif_locked);
            default:
                return getString(R.string.chat_offline);
        }
    }

    /** 把 TideClient 的稳定错误码翻成人话，别把原始 401/500 直接丢给用户。 */
    private String friendlyError(Exception e) {
        if (e instanceof TideClient.TideException) {
            TideClient.TideException te = (TideClient.TideException) e;
            if ("refresh-transient".equals(te.code)) return "网络暂时不可用，请稍后重试";
            if ("refresh-protocol".equals(te.code) || "protocol-error".equals(te.code)) {
                return getString(R.string.chat_protocol);
            }
            if ("refresh-auth-failed".equals(te.code)) return getString(R.string.chat_failed_plain);
            if (te.status == 401) return getString(R.string.chat_failed_plain);
            if ("attachment-too-large".equals(te.code)) {
                return getString(R.string.file_too_large, FileSafety.MAX_UPLOAD_BYTES / (1024 * 1024));
            }
            if ("attachment-quota-exceeded".equals(te.code)) return "这个会话的附件空间已满";
            if ("quota-exceeded".equals(te.code)) return te.getMessage();
            if ("attachment-not-found".equals(te.code)) return "附件已不存在";
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    // ── 发送 ─────────────────────────────────────────────────────────────

    private boolean handleVoiceTouch(MotionEvent event) {
        if (!canSend) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!hasRecordPermission()) {
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
                    Toast.makeText(this, R.string.voice_permission_denied, Toast.LENGTH_LONG).show();
                    return true;
                }
                voiceTouchY = event.getRawY();
                startVoiceRecording();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (voiceRecorder != null && voiceTouchY - event.getRawY() > dp(60)) {
                    voiceCancel = true;
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (voiceRecorder != null) finishVoiceRecording(voiceCancel);
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (voiceRecorder != null) finishVoiceRecording(true);
                return true;
            default:
                return true;
        }
    }

    private boolean hasRecordPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startVoiceRecording() {
        if (voiceRecorder != null) return;
        File dir = new File(getCacheDir(), "voice");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Toast.makeText(this, R.string.voice_recording_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            voiceFile = File.createTempFile("voice-", ".m4a", dir);
            voiceRecorder = new MediaRecorder();
            voiceRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            voiceRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            voiceRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            voiceRecorder.setAudioChannels(1);
            voiceRecorder.setAudioSamplingRate(44_100);
            voiceRecorder.setAudioEncodingBitRate(64_000);
            voiceRecorder.setOutputFile(voiceFile.getAbsolutePath());
            voiceRecorder.prepare();
            voiceRecorder.start();
            voiceStartedAt = System.currentTimeMillis();
            voiceCancel = false;
            showVoiceOverlay();
            main.postDelayed(voiceMax, MAX_VOICE_MS);
            main.post(voiceOverlayTick);
        } catch (Exception error) {
            releaseVoiceRecorder();
            if (voiceFile != null) {
                voiceFile.delete();
                voiceFile = null;
            }
            Toast.makeText(this, R.string.voice_recording_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showVoiceOverlay() {
        if (voicePopup == null) {
            voicePopupText = new TextView(this);
            voicePopupText.setTextColor(Color.WHITE);
            voicePopupText.setTextSize(14);
            voicePopupText.setGravity(Gravity.CENTER);
            voicePopupText.setPadding(dp(18), dp(12), dp(18), dp(12));
            voicePopupText.setBackgroundColor(0xCC222222);
            voicePopup = new PopupWindow(
                    voicePopupText,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    false
            );
            voicePopup.setOutsideTouchable(false);
        }
        voicePopupText.setText(getString(R.string.voice_recording) + "\n0:00");
        voicePopup.showAtLocation(
                findViewById(android.R.id.content),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL,
                0,
                dp(130)
        );
    }

    private void finishVoiceRecording(boolean cancel) {
        if (voiceRecorder == null) return;
        main.removeCallbacks(voiceMax);
        main.removeCallbacks(voiceOverlayTick);
        boolean shouldCancel = cancel || voiceCancel;
        long elapsed = System.currentTimeMillis() - voiceStartedAt;
        releaseVoiceRecorder();
        if (voicePopup != null) voicePopup.dismiss();
        File recorded = voiceFile;
        voiceFile = null;
        if (recorded == null || !recorded.exists()) return;
        long duration = measuredVoiceDuration(recorded);
        if (duration <= 0) duration = elapsed;
        if (shouldCancel) {
            recorded.delete();
            return;
        }
        if (duration < 1000L) {
            recorded.delete();
            Toast.makeText(this, R.string.voice_too_short, Toast.LENGTH_SHORT).show();
            return;
        }
        sendVoice(recorded, duration);
    }

    private void releaseVoiceRecorder() {
        MediaRecorder recorder = voiceRecorder;
        voiceRecorder = null;
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.reset(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
        }
    }

    private long measuredVoiceDuration(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return raw == null ? 0L : Long.parseLong(raw);
        } catch (Exception ignored) {
            return 0L;
        } finally {
            try { retriever.release(); } catch (Exception ignored) {}
        }
    }

    private void sendVoice(File file, long durationMs) {
        final String cid = conversationId;
        if (cid == null) {
            file.delete();
            return;
        }
        String clientMsgId = UUID.randomUUID().toString();
        final String replyId = pendingReply == null ? null : pendingReply.id;
        clearReply();
        Message pending = new Message(
                clientMsgId,
                clientMsgId,
                0,
                "user",
                "",
                System.currentTimeMillis(),
                true,
                Collections.emptyList(),
                durationMs
        );
        adapter.add(pending);
        scrollToBottom();
        setUploadButtonsEnabled(false);
        Toast.makeText(this, R.string.voice_uploading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                byte[] bytes = readFileBytes(file);
                JSONObject uploaded = client.uploadAttachment(
                        cid,
                        bytes,
                        "voice-" + clientMsgId.substring(0, 8) + ".m4a",
                        "audio/mp4"
                );
                String attachmentId = uploaded.optString(
                        "attachment_id",
                        uploaded.optString("id", "")
                );
                if (attachmentId.isEmpty()) throw new IOException("上传响应缺少附件 id");
                JSONArray ids = new JSONArray().put(attachmentId);
                JSONObject response = client.postPrompt(
                        cid, "", clientMsgId, ids, replyId, durationMs
                );
                Message real = messageFromResponse(response);
                if (real == null) real = findSavedMessage(cid, clientMsgId);
                final Message confirmed = real;
                main.post(() -> {
                    setUploadButtonsEnabled(true);
                    if (confirmed != null) {
                        adapter.confirmPending(clientMsgId, confirmed);
                    } else if (!response.optBoolean("queued", false)
                            && !response.optBoolean("delivered", false)) {
                        adapter.markFailed(clientMsgId);
                        Toast.makeText(this, "网关没接收这条语音", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception error) {
                String message = friendlyError(error);
                main.post(() -> {
                    setUploadButtonsEnabled(true);
                    adapter.markFailed(clientMsgId);
                    Toast.makeText(
                            this,
                            getString(R.string.voice_send_failed, message),
                            Toast.LENGTH_LONG
                    ).show();
                });
            } finally {
                file.delete();
            }
        }, "tide-voice-upload").start();
    }

    private byte[] readFileBytes(File file) throws IOException {
        if (!file.isFile()) throw new IOException(getString(R.string.file_read_failed));
        if (file.length() > FileSafety.MAX_UPLOAD_BYTES) {
            throw new IOException(getString(
                    R.string.file_too_large,
                    FileSafety.MAX_UPLOAD_BYTES / (1024 * 1024)
            ));
        }
        try (InputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private void pickImage() {
        if (conversationId == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        try {
            startActivityForResult(intent, REQ_PICK_IMAGE);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的图片选择器", Toast.LENGTH_LONG).show();
        }
    }

    private void pickFile() {
        if (conversationId == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(intent, REQ_PICK_FILE);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SEARCH) {
            String messageId = data == null
                    ? null : data.getStringExtra(SearchActivity.EXTRA_MESSAGE_ID);
            if (resultCode == RESULT_OK && !TextUtils.isEmpty(messageId)) {
                jumpToMessageId(messageId);
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQ_PICK_IMAGE) {
            String caption = inputMessage.getText().toString().trim();
            inputMessage.setText("");
            sendImage(data.getData(), caption);
        } else if (requestCode == REQ_PICK_FILE) {
            String caption = inputMessage.getText().toString().trim();
            inputMessage.setText("");
            sendFile(data.getData(), caption);
        }
    }

    private void sendImage(Uri uri, String caption) {
        final String cid = conversationId;
        if (cid == null) return;
        setUploadButtonsEnabled(false);
        Toast.makeText(this, R.string.uploading_image, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String clientMsgId = UUID.randomUUID().toString();
            Message pending = null;
            try {
                PreparedImage prepared = prepareImage(uri);
                JSONObject uploaded = client.uploadAttachment(
                        cid,
                        prepared.bytes,
                        prepared.filename,
                        prepared.mediaType
                );
                String attachmentId = uploaded.optString(
                        "attachment_id",
                        uploaded.optString("id", "")
                );
                if (attachmentId.isEmpty()) throw new IOException("上传响应缺少附件 id");
                Attachment attachment = new Attachment(
                        attachmentId,
                        uploaded.optString("name", prepared.filename),
                        uploaded.optString("media_type", prepared.mediaType),
                        uploaded.optLong("bytes", prepared.bytes.length)
                );
                pending = new Message(
                        clientMsgId,
                        clientMsgId,
                        0,
                        "user",
                        caption,
                        System.currentTimeMillis(),
                        true,
                        Collections.singletonList(attachment)
                );
                Message adding = pending;
                main.post(() -> {
                    if (leaving || !cid.equals(conversationId)) return;
                    adapter.add(adding);
                    scrollToBottom();
                });
                JSONArray ids = new JSONArray().put(attachmentId);
                JSONObject response = client.postPrompt(cid, caption, clientMsgId, ids);
                Message real = messageFromResponse(response);
                if (real == null) real = findSavedMessage(cid, clientMsgId);
                final Message confirmed = real;
                main.post(() -> {
                    setUploadButtonsEnabled(true);
                    if (confirmed != null) adapter.confirmPending(clientMsgId, confirmed);
                    if (!response.optBoolean("queued", false)
                            && !response.optBoolean("delivered", false)) {
                        Toast.makeText(this, "网关没接收这条图片", Toast.LENGTH_LONG).show();
                        if (confirmed == null) adapter.markFailed(clientMsgId);
                    }
                });
            } catch (Exception e) {
                String message = friendlyError(e);
                main.post(() -> {
                    setUploadButtonsEnabled(true);
                    adapter.markFailed(clientMsgId);
                    Toast.makeText(this, "图片发送失败：" + message, Toast.LENGTH_LONG).show();
                });
            }
        }, "tide-image-upload").start();
    }

    private void sendFile(Uri uri, String caption) {
        final String cid = conversationId;
        if (cid == null) return;
        setUploadButtonsEnabled(false);
        Toast.makeText(this, R.string.uploading_file, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String clientMsgId = UUID.randomUUID().toString();
            Message pending = null;
            try {
                PreparedFile prepared = prepareFile(uri);
                JSONObject uploaded = client.uploadAttachment(
                        cid,
                        prepared.bytes,
                        prepared.filename,
                        prepared.mediaType
                );
                String attachmentId = uploaded.optString(
                        "attachment_id",
                        uploaded.optString("id", "")
                );
                if (attachmentId.isEmpty()) throw new IOException("上传响应缺少附件 id");
                Attachment attachment = new Attachment(
                        attachmentId,
                        uploaded.optString("name", prepared.filename),
                        uploaded.optString("media_type", prepared.mediaType),
                        uploaded.optLong("bytes", prepared.bytes.length)
                );
                pending = new Message(
                        clientMsgId,
                        clientMsgId,
                        0,
                        "user",
                        caption,
                        System.currentTimeMillis(),
                        true,
                        Collections.singletonList(attachment)
                );
                Message adding = pending;
                main.post(() -> {
                    if (leaving || !cid.equals(conversationId)) return;
                    adapter.add(adding);
                    scrollToBottom();
                });
                JSONArray ids = new JSONArray().put(attachmentId);
                JSONObject response = client.postPrompt(cid, caption, clientMsgId, ids);
                Message real = messageFromResponse(response);
                if (real == null) real = findSavedMessage(cid, clientMsgId);
                final Message confirmed = real;
                main.post(() -> {
                    setUploadButtonsEnabled(true);
                    if (confirmed != null) adapter.confirmPending(clientMsgId, confirmed);
                    if (!response.optBoolean("queued", false)
                            && !response.optBoolean("delivered", false)) {
                        Toast.makeText(this, "网关没接收这条文件", Toast.LENGTH_LONG).show();
                        if (confirmed == null) adapter.markFailed(clientMsgId);
                    }
                });
            } catch (Exception e) {
                String message = friendlyError(e);
                main.post(() -> {
                    setUploadButtonsEnabled(true);
                    adapter.markFailed(clientMsgId);
                    Toast.makeText(this, getString(R.string.file_send_failed, message),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "tide-file-upload").start();
    }

    private PreparedFile prepareFile(Uri uri) throws Exception {
        ContentResolver resolver = getContentResolver();
        String displayName = null;
        long declaredSize = -1;
        try (Cursor cursor = resolver.query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    declaredSize = cursor.getLong(sizeIndex);
                }
            }
        }
        if (TextUtils.isEmpty(displayName)) displayName = uri.getLastPathSegment();
        String filename = FileSafety.sanitizeFileName(displayName);
        String mediaType = FileSafety.normalizeMimeType(resolver.getType(uri));
        if (declaredSize > FileSafety.MAX_UPLOAD_BYTES) {
            throw new IOException(getString(
                    R.string.file_too_large,
                    FileSafety.MAX_UPLOAD_BYTES / (1024 * 1024)
            ));
        }
        byte[] bytes = readFile(resolver, uri, declaredSize);
        return new PreparedFile(bytes, filename, mediaType);
    }

    private byte[] readFile(ContentResolver resolver, Uri uri, long declaredSize)
            throws IOException {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException(getString(R.string.file_read_failed));
            int initial = declaredSize > 0 && declaredSize <= FileSafety.MAX_UPLOAD_BYTES
                    ? (int) declaredSize
                    : 8192;
            ByteArrayOutputStream out = new ByteArrayOutputStream(initial);
            byte[] buffer = new byte[16 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) >= 0) {
                total += n;
                if (total > FileSafety.MAX_UPLOAD_BYTES) {
                    throw new IOException(getString(
                            R.string.file_too_large,
                            FileSafety.MAX_UPLOAD_BYTES / (1024 * 1024)
                    ));
                }
                out.write(buffer, 0, n);
            }
            if (total == 0) throw new IOException(getString(R.string.file_empty));
            return out.toByteArray();
        } catch (IOException e) {
            if (e.getMessage() != null && !e.getMessage().isEmpty()) throw e;
            throw new IOException(getString(R.string.file_read_failed), e);
        }
    }

    private void setUploadButtonsEnabled(boolean enabled) {
        boolean on = enabled && canSend;
        if (attachButton != null) attachButton.setEnabled(on);
        if (voiceButton != null) voiceButton.setEnabled(on);
    }

    private void setComposerEnabled(boolean enabled) {
        canSend = enabled;
        if (inputMessage == null) return;
        inputMessage.setEnabled(enabled);
        inputMessage.setBackgroundResource(enabled
                ? R.drawable.bg_chat_input : R.drawable.bg_chat_input_dis);
        inputMessage.setHint(enabled ? R.string.hint_message : R.string.chat_input_offline);
        if (attachButton != null) attachButton.setEnabled(enabled);
        if (voiceButton != null) voiceButton.setEnabled(enabled);
        if (!enabled) {
            hideStickerPanel();
            hideAttachPanel();
        }
        updateSendButton();
    }

    private void updateSendButton() {
        if (btnSend == null || inputMessage == null) return;
        boolean enabled = canSend
                && !inputMessage.getText().toString().trim().isEmpty();
        btnSend.setEnabled(enabled);
        btnSend.setBackgroundResource(enabled
                ? R.drawable.bg_send : R.drawable.bg_send_dis);
        btnSend.setAlpha(enabled ? 1f : 0.55f);
    }

    private PreparedImage prepareImage(Uri uri) throws Exception {
        ContentResolver resolver = getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = resolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("无法读取图片");
        }
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 3200) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream in = resolver.openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null) throw new IOException("图片解码失败");

        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in != null) {
                orientation = new ExifInterface(in).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                );
            }
        } catch (Exception ignored) {
        }
        Bitmap oriented = orient(bitmap, orientation);
        int side = Math.max(oriented.getWidth(), oriented.getHeight());
        if (side > 1600) {
            float scale = 1600f / side;
            Bitmap scaled = Bitmap.createScaledBitmap(
                    oriented,
                    Math.max(1, Math.round(oriented.getWidth() * scale)),
                    Math.max(1, Math.round(oriented.getHeight() * scale)),
                    true
            );
            if (scaled != oriented) oriented.recycle();
            oriented = scaled;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!oriented.compress(Bitmap.CompressFormat.JPEG, 88, out)) {
            oriented.recycle();
            throw new IOException("图片压缩失败");
        }
        oriented.recycle();
        String filename = "image-" + UUID.randomUUID().toString().substring(0, 8) + ".jpg";
        return new PreparedImage(out.toByteArray(), filename, "image/jpeg");
    }

    private static Bitmap orient(Bitmap source, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1, -1);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90);
                matrix.postScale(-1, 1);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90);
                break;
            default:
                return source;
        }
        Bitmap rotated = Bitmap.createBitmap(
                source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
        if (rotated != source) source.recycle();
        return rotated;
    }

    private void showImagePreview(String attachmentId, String name) {
        startActivity(new Intent(this, ImageViewerActivity.class)
                .putExtra(ImageViewerActivity.EXTRA_ATTACHMENT_ID, attachmentId)
                .putExtra(ImageViewerActivity.EXTRA_NAME, name));
    }

    private void openFile(Attachment attachment) {
        if (attachment == null || TextUtils.isEmpty(attachment.id)) return;
        Toast.makeText(this, R.string.file_downloading, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File file = downloadToShared(attachment);
                main.post(() -> {
                    if (leaving || isFinishing()) return;
                    adapter.markDownloaded(attachment.id);
                    launchViewer(file, attachment);
                });
            } catch (Exception e) {
                String message = friendlyError(e);
                main.post(() -> {
                    if (leaving || isFinishing()) return;
                    Toast.makeText(this,
                            getString(R.string.file_download_failed, message),
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "tide-file-download").start();
    }

    private File downloadToShared(Attachment attachment) throws Exception {
        byte[] bytes = client.downloadAttachment(attachment.id);
        File dir = TideFileProvider.sharedDir(this);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建文件缓存目录");
        }
        File temp = File.createTempFile("tide-download-", ".part", dir);
        File target = new File(dir, FileSafety.sanitizeFileName(attachment.id) + ".bin");
        try {
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            if (target.exists() && !target.delete()) {
                throw new IOException("无法替换旧缓存文件");
            }
            if (!temp.renameTo(target)) {
                throw new IOException("无法保存下载文件");
            }
            return target;
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    private void launchViewer(File file, Attachment attachment) {
        Uri contentUri;
        try {
            contentUri = TideFileProvider.uriFor(
                    this, file, attachment.name, attachment.mimeType);
        } catch (IOException e) {
            Toast.makeText(this, R.string.file_viewer_failed, Toast.LENGTH_LONG).show();
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(contentUri, FileSafety.normalizeMimeType(attachment.mimeType))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        view.setClipData(ClipData.newRawUri(FileSafety.sanitizeFileName(attachment.name),
                contentUri));
        try {
            startActivity(view);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.file_no_viewer, Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.file_viewer_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static final class PreparedFile {
        final byte[] bytes;
        final String filename;
        final String mediaType;

        PreparedFile(byte[] bytes, String filename, String mediaType) {
            this.bytes = bytes;
            this.filename = filename;
            this.mediaType = mediaType;
        }
    }

    private static final class PreparedImage {
        final byte[] bytes;
        final String filename;
        final String mediaType;

        PreparedImage(byte[] bytes, String filename, String mediaType) {
            this.bytes = bytes;
            this.filename = filename;
            this.mediaType = mediaType;
        }
    }

    private void sendMessage() {
        if (!canSend) return;
        String text = inputMessage.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        inputMessage.setText("");
        sendText(text);
    }

    private int stickerCellSize() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int available = metrics.widthPixels - dp(16) - dp(24);
        return Math.max(dp(56), available / 4);
    }

    private void toggleAttachPanel() {
        if (attachPanel == null) return;
        if (attachPanel.getVisibility() == View.VISIBLE) {
            hideAttachPanel();
        } else {
            showAttachPanel();
        }
    }

    private void showAttachPanel() {
        if (attachPanel == null || !canSend) return;
        hideKeyboard();
        hideStickerPanel();
        attachPanel.setVisibility(View.VISIBLE);
    }

    private void hideAttachPanel() {
        if (attachPanel != null && attachPanel.getVisibility() != View.GONE) {
            attachPanel.setVisibility(View.GONE);
        }
    }

    private void showStickerPanel() {
        if (stickerPanel == null || !canSend) return;
        hideAttachPanel();
        hideKeyboard();
        int height = Math.min(
                (int) (getResources().getDisplayMetrics().heightPixels * 0.4),
                dp(360)
        );
        ViewGroup.LayoutParams params = stickerPanel.getLayoutParams();
        params.height = Math.max(dp(180), height);
        stickerPanel.setLayoutParams(params);
        stickerPanel.setVisibility(View.VISIBLE);
        selectStickerTab(stickerRecentOnly);
        loadStickers();
    }

    private void hideStickerPanel() {
        if (stickerPanel != null && stickerPanel.getVisibility() != View.GONE) {
            stickerPanel.setVisibility(View.GONE);
        }
    }

    @Override
    public void onBackPressed() {
        if (stickerPanel != null && stickerPanel.getVisibility() == View.VISIBLE) {
            hideStickerPanel();
            return;
        }
        if (attachPanel != null && attachPanel.getVisibility() == View.VISIBLE) {
            hideAttachPanel();
            return;
        }
        super.onBackPressed();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && inputMessage != null) {
            imm.hideSoftInputFromWindow(inputMessage.getWindowToken(), 0);
        }
        if (inputMessage != null) inputMessage.clearFocus();
    }

    private void selectStickerTab(boolean recentOnly) {
        stickerRecentOnly = recentOnly;
        if (stickerTabRecent != null && stickerTabAll != null) {
            stickerTabRecent.setTextColor(getResources().getColor(
                    recentOnly ? R.color.teal_dark : R.color.ink_3));
            stickerTabAll.setTextColor(getResources().getColor(
                    recentOnly ? R.color.ink_3 : R.color.teal_dark));
            stickerTabRecent.setTypeface(null, recentOnly ? Typeface.BOLD : Typeface.NORMAL);
            stickerTabAll.setTypeface(null, recentOnly ? Typeface.NORMAL : Typeface.BOLD);
        }
        renderStickers();
    }

    private void loadStickers() {
        if (stickerLoading) return;
        stickerLoading = true;
        new Thread(() -> {
            try {
                JSONObject response = client.listStickers();
                JSONArray array = response.optJSONArray("stickers");
                List<Sticker> loaded = new ArrayList<>();
                if (array != null) {
                    for (int i = 0; i < array.length(); i++) {
                        Sticker sticker = Sticker.fromJson(array.optJSONObject(i));
                        if (sticker != null) loaded.add(sticker);
                    }
                }
                main.post(() -> {
                    stickerLoading = false;
                    stickers.clear();
                    stickers.addAll(loaded);
                    // 新用户没有“最近使用”，默认停在「最近」会误报“还没有表情包”。
                    if (stickerRecentOnly && Sticker.filter(stickers, true).isEmpty() && !stickers.isEmpty()) {
                        selectStickerTab(false);
                    } else {
                        renderStickers();
                    }
                });
            } catch (Exception error) {
                String message = friendlyError(error);
                main.post(() -> {
                    stickerLoading = false;
                    Toast.makeText(this, "表情加载失败：" + message, Toast.LENGTH_SHORT).show();
                });
            }
        }, "tide-stickers").start();
    }

    private void renderStickers() {
        if (stickerAdapter == null || stickerGrid == null || stickerEmpty == null) return;
        List<Sticker> visible = Sticker.filter(stickers, stickerRecentOnly);
        stickerAdapter.setItems(visible);
        boolean empty = visible.isEmpty();
        stickerGrid.setVisibility(empty ? View.GONE : View.VISIBLE);
        stickerEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty && stickerRecentOnly && !stickers.isEmpty()) {
            stickerEmpty.setText(R.string.sticker_empty_recent);
        } else {
            stickerEmpty.setText(R.string.sticker_empty);
        }
    }

    private void sendSticker(Sticker sticker) {
        String cid = conversationId;
        if (cid == null || !canSend || sticker == null) return;
        hideStickerPanel();
        final String replyId = pendingReply == null ? null : pendingReply.id;
        clearReply();
        String clientMsgId = UUID.randomUUID().toString();
        Attachment attachment = new Attachment(sticker.attachmentId, sticker.name, "image/png", 0);
        Message pending = Message.sticker(
                clientMsgId,
                clientMsgId,
                0,
                "user",
                sticker.id,
                sticker.name,
                Collections.singletonList(attachment),
                System.currentTimeMillis(),
                true,
                replyId
        );
        adapter.add(pending);
        scrollToBottom();

        new Thread(() -> {
            try {
                JSONObject response = client.postSticker(cid, sticker.id, clientMsgId, replyId);
                Message real = messageFromResponse(response);
                if (real == null) real = findSavedMessage(cid, clientMsgId);
                final Message confirmed = real;
                main.post(() -> {
                    if (confirmed != null) {
                        adapter.confirmPending(clientMsgId, confirmed);
                    } else {
                        adapter.markFailed(clientMsgId);
                    }
                });
            } catch (Exception error) {
                String message = friendlyError(error);
                main.post(() -> {
                    adapter.markFailed(clientMsgId);
                    Toast.makeText(this, "表情发送失败：" + message, Toast.LENGTH_LONG).show();
                });
            }
        }, "tide-sticker-send").start();
    }

    private void sendText(String text) {
        String cid = conversationId;
        if (cid == null || !canSend) return;
        final String replyId = pendingReply == null ? null : pendingReply.id;
        clearReply();
        String clientMsgId = UUID.randomUUID().toString();
        Message pending = new Message(clientMsgId, 0, "user", text, System.currentTimeMillis(), true);
        adapter.add(pending);
        scrollToBottom();

        new Thread(() -> {
            try {
                JSONObject r = client.postPrompt(cid, text, clientMsgId, null, replyId);
                boolean queued = r.optBoolean("queued", false);
                boolean delivered = r.optBoolean("delivered", false);
                Message real = messageFromResponse(r);
                if (real == null) real = findSavedMessage(cid, clientMsgId);
                final Message confirmed = real;
                main.post(() -> {
                    if (!queued && !delivered) {
                        Toast.makeText(this, "网关没接收这条消息", Toast.LENGTH_LONG).show();
                    }
                    if (confirmed != null) {
                        adapter.confirmPending(clientMsgId, confirmed);
                    } else if (!queued && !delivered) {
                        adapter.markFailed(clientMsgId);
                    }
                });
            } catch (Exception e) {
                String msg = friendlyError(e);
                main.post(() -> {
                    adapter.markFailed(clientMsgId);
                    Toast.makeText(this, "发送失败：" + msg, Toast.LENGTH_LONG).show();
                });
            }
        }, "tide-send").start();
    }

    private void resendAttachments(Message source) {
        String cid = conversationId;
        if (cid == null || !canSend || source.attachments.isEmpty()) return;
        String clientMsgId = UUID.randomUUID().toString();
        Message pending = new Message(
                clientMsgId,
                clientMsgId,
                0,
                "user",
                source.text,
                System.currentTimeMillis(),
                true,
                source.attachments,
                source.voiceDurationMs
        );
        adapter.add(pending);
        scrollToBottom();
        JSONArray ids = new JSONArray();
        for (Attachment attachment : source.attachments) ids.put(attachment.id);

        new Thread(() -> {
            try {
                JSONObject response = client.postPrompt(
                        cid, source.text, clientMsgId, ids, null, source.voiceDurationMs
                );
                Message real = messageFromResponse(response);
                if (real == null) real = findSavedMessage(cid, clientMsgId);
                final Message confirmed = real;
                main.post(() -> {
                    if (confirmed != null) {
                        adapter.confirmPending(clientMsgId, confirmed);
                    } else if (!response.optBoolean("queued", false)
                            && !response.optBoolean("delivered", false)) {
                        adapter.markFailed(clientMsgId);
                    }
                });
            } catch (Exception e) {
                String msg = friendlyError(e);
                main.post(() -> {
                    adapter.markFailed(clientMsgId);
                    Toast.makeText(this, "发送失败：" + msg, Toast.LENGTH_LONG).show();
                });
            }
        }, "tide-send-attachments").start();
    }

    private Message messageFromResponse(JSONObject response) {
        JSONObject message = response.optJSONObject("message");
        return message == null ? null : Message.fromJson(message);
    }

    /**
     * 兼容旧网关：POST 只返回 queued/delivered 时，按幂等键从刚写好的历史里找回真消息。
     * 新网关直接带 message，这个兜底不会发请求。GET /messages 的约定是“默认返回最新 N 条”，
     * 这里依赖该语义；若以后改成升序分页，必须同步换游标，不能静默沿用。
     */
    private Message findSavedMessage(String cid, String clientMsgId) {
        try {
            JSONObject page = client.listMessages(cid, 0, 50);
            JSONArray arr = TideClient.messagesOf(page);
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Message message = Message.fromJson(o);
                if (clientMsgId.equals(message.clientMsgId)) return message;
            }
        } catch (Exception ignored) {
            // SSE 仍是主通道；这里只是发送成功后的即时回执兜底。
        }
        return null;
    }

    private void markReadThrough(long seq) {
        long target = Math.min(seq, readGuardSeq);
        if (target > pendingReadSeq) pendingReadSeq = target;
        if (pendingReadSeq <= confirmedReadSeq || readInFlight || readFlushPosted) return;
        scheduleReadFlush();
    }

    private void reloadIfBehind() {
        String cid = conversationId;
        if (cid == null) return;
        reloadIfBehind(session.lastSeq(cid));
    }

    private void reloadIfBehind(long expectedSeq) {
        String cid = conversationId;
        if (cid == null || reloadInFlight) return;
        long known = adapter.lastSeq();
        long expected = Math.max(expectedSeq, session.lastSeq(cid));
        if (expected <= known && readGuardSeq == Long.MAX_VALUE) return;
        long floor = readGuardSeq == Long.MAX_VALUE ? known : readGuardSeq;
        final boolean loadingHistory = historyHasMore;
        final long previousGuard = readGuardSeq;
        readGuardSeq = floor;
        reloadInFlight = true;
        new Thread(() -> {
            List<Message> fresh = new ArrayList<>();
            long oldestFetched = Long.MAX_VALUE;
            boolean moreBefore = historyHasMore;
            try {
                long before = reloadCursor;
                for (int pageNo = 0; pageNo < MAX_RELOAD_PAGES; pageNo++) {
                    JSONObject page = client.listMessages(cid, before, 50);
                    JSONArray arr = TideClient.messagesOf(page);
                    moreBefore = page.optBoolean("has_more", false);
                    long oldest = Long.MAX_VALUE;
                    int count = 0;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.optJSONObject(i);
                        if (item == null) continue;
                        Message message = Message.fromJson(item);
                        fresh.add(message);
                        count++;
                        if (message.seq > 0) {
                            oldest = Math.min(oldest, message.seq);
                            oldestFetched = Math.min(oldestFetched, message.seq);
                        }
                    }
                    if (loadingHistory) {
                        if (!moreBefore) break;
                    } else if (count < 50 || oldest <= floor + 1) {
                        break;
                    }
                    before = oldest;
                }
            } catch (Exception ignored) {
                main.post(() -> {
                    reloadInFlight = false;
                    readGuardSeq = previousGuard;
                });
                return;
            }
            final long lastFetched = oldestFetched;
            final boolean stillMore = moreBefore;
            main.post(() -> {
                reloadInFlight = false;
                if (leaving || !cid.equals(conversationId)) return;
                Collections.sort(fresh, (a, b) -> Long.compare(a.seq, b.seq));
                boolean changed = false;
                for (Message message : fresh) {
                    if (message.seq <= known) continue;
                    changed = true;
                    if (message.isUser()) {
                        if (message.clientMsgId != null && !message.clientMsgId.isEmpty()) {
                            adapter.confirmPending(message.clientMsgId, message);
                        } else {
                            adapter.confirmPendingByText(message);
                        }
                    } else if (!adapter.containsSeq(message.seq)) {
                        adapter.add(message);
                    }
                }
                if (lastFetched != Long.MAX_VALUE
                        && (oldestLoadedSeq == 0 || lastFetched < oldestLoadedSeq)) {
                    oldestLoadedSeq = lastFetched;
                }
                historyHasMore = stillMore;
                long anchor = previousGuard == Long.MAX_VALUE ? known : previousGuard;
                boolean readGap = lastFetched != Long.MAX_VALUE && lastFetched > anchor + 1;
                readGuardSeq = readGap ? anchor : Long.MAX_VALUE;
                if (readGap && lastFetched != Long.MAX_VALUE) {
                    reloadCursor = lastFetched;
                } else if (historyHasMore) {
                    reloadCursor = oldestLoadedSeq;
                } else {
                    reloadCursor = 0;
                }
                markReadThrough(readGuardSeq == Long.MAX_VALUE
                        ? adapter.lastSeq()
                        : readGuardSeq);
                if (readGap) {
                    Toast.makeText(this,
                            "新消息还有缺口，先不标记已读",
                            Toast.LENGTH_LONG).show();
                }
                if (changed) scrollToBottom();
            });
        }, "tide-reload").start();
    }

    private void scheduleReadFlush() {
        readFlushPosted = true;
        main.postDelayed(flushRead, 150);
    }

    private void scrollToBottom() {
        adapter.clearUnreadDivider();
        listMessages.post(() -> {
            if (adapter.getCount() > 0) {
                listMessages.setSelection(adapter.getCount() - 1);
            }
        });
    }

    private void scrollToPosition(int position) {
        listMessages.post(() -> listMessages.setSelection(Math.max(0, position)));
    }

    // ── 生命周期：只加减监听，不断连接 ───────────────────────────────────

    @Override
    protected void onStart() {
        super.onStart();
        if (!TideApp.ensureUnlocked(this)) return;
        // authDead 时不能 attachService：Service 没起过，currentState() 是 OFFLINE，
        // 会把「登录已失效」刷成「已断开」，连点击入口一起换掉。
        if (!authDead && conversationId != null) {
            reloadIfBehind();
            attachService();
        }
    }

    @Override
    protected void onStop() {
        if (voiceRecorder != null) finishVoiceRecording(true);
        TideService.removeListener(this);
        main.removeCallbacks(flushRead);
        readFlushPosted = false;
        flushRead.run();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        leaving = true;
        if (voiceRecorder != null) finishVoiceRecording(true);
        if (voicePopup != null) voicePopup.dismiss();
        main.removeCallbacks(typingWatchdog);
        main.removeCallbacks(flushRead);
        main.removeCallbacks(voiceMax);
        main.removeCallbacks(voiceOverlayTick);
        readExecutor.shutdown();
        if (adapter != null) adapter.shutdown();
        if (stickerAdapter != null) stickerAdapter.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (authDead) return;
        if (conversationId != null) {
            int state = TideService.currentState();
            textConn.setText(stateText(state));
            setComposerEnabled(state == TideService.STATE_ONLINE);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        refreshBotProfile();
    }

    private void refreshBotProfile() {
        if (botProfileLoading || client == null) return;
        botProfileLoading = true;
        lastProfileRefreshAt = System.currentTimeMillis();
        new Thread(() -> {
            try {
                JSONObject profile = client.botProfile();
                AiProfileStore.apply(this, profile);
                runOnUiThread(() -> {
                    botProfileLoading = false;
                    if (adapter != null) adapter.notifyDataSetChanged();
                    if ("direct".equals(currentKind) && textTitle != null) {
                        textTitle.setText(AiProfileStore.name(this, "汐语"));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> botProfileLoading = false);
            }
        }, "tide-chat-profile").start();
    }

    // ── 通知权限（Android 13+ 不授权，前台服务起不来） ────────────────────

    private void askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(
                    this,
                    granted ? "麦克风权限已开启，请再按住录音" : getString(R.string.voice_permission_denied),
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        if (requestCode != REQ_NOTIFICATION) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Toast.makeText(this,
                    "没有通知权限，前台服务会被系统拦掉，退到后台就收不到消息了",
                    Toast.LENGTH_LONG).show();
        }
    }
}
