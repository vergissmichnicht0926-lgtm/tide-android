package com.whaleeye.tide;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 常驻前台服务：唯一一条 SSE 连接的所有者。
 *
 * 为什么要在 Service 里：Activity 退到后台就可能被回收，而 SSE 一断，模型的回复只能
 * 等用户再打开 App 才补出来。把连接搬到前台服务，锁屏、切 App、进程被系统轻杀都能续上
 * （重杀要靠 START_STICKY 重启 + Session 里的水位）。
 *
 * 三件事：
 *  - 连接循环：开流 → 断了按指数退避重连，带 since_seq 补齐
 *  - 消息分发给已绑定的 Activity（没人绑就攒未读、更新通知）
 *  - 通知：低优先级常驻，显示连接状态与未读数
 *
 * 不做什么：发送消息仍由 Activity 直接调 TideClient.postPrompt（一次 HTTP，不需要
 * 走服务），这里只负责收。
 */
public final class TideService extends Service {

    public static final String ACTION_START = "com.whaleeye.tide.action.START";
    public static final String ACTION_STOP = "com.whaleeye.tide.action.STOP";
    public static final String EXTRA_CONVERSATION_ID = "conversation_id";
    public static final String EXTRA_CONVERSATION_TITLE = "conversation_title";

    /** 连接状态，给通知和 Activity 顶栏用。 */
    public static final int STATE_CONNECTING = 0;
    public static final int STATE_ONLINE = 1;
    public static final int STATE_OFFLINE = 2;
    /** 终态：凭证真死了，只能重新登录。 */
    public static final int STATE_FAILED = 3;
    /** 终态：网关不认这个请求（含字段/版本不匹配），重试无用，更新 App 才可能好。 */
    public static final int STATE_PROTOCOL = 4;
    /** 应用锁已落下：不读凭据、不重连，但已有 SSE 继续收；解锁后恢复。 */
    public static final int STATE_LOCKED = 5;

    private static final String CHANNEL_ID = "tide-stream";
    private static final String MESSAGE_CHANNEL_ID = "tide-messages";
    private static final int NOTIFICATION_ID = 0x7101;
    private static final long RECONNECT_BASE_MS = 1_000L;
    private static final long RECONNECT_MAX_MS = 30_000L;
    private static final long LOCKED_WAIT_MS = 30_000L;
    /** 连续 401 且续期成功、但流仍未 ready 的上限；超过就判结构性鉴权失败。 */
    private static final int MAX_STREAM_AUTH_RETRIES = 3;

    /** Activity 侧监听。用静态列表是因为工程不引 AndroidX（没有 LocalBroadcast）。 */
    public interface Listener {
        void onReady(long latestSeq);

        void onMessage(Message message);

        void onTurn(boolean start, String reason);

        void onState(int state);

        void onUnreadChanged(String conversationId);

        void onHistoryTruncated(String conversationId, long latestSeq, int loadedMessages);

        /** 会话级事件：conversation.updated / message.recalled / conversation.deleted。 */
        default void onEvent(String conversationId, String type, JSONObject event) {
        }
    }

    private static final class Registration {
        final String conversationId;
        final Listener listener;

        Registration(String conversationId, Listener listener) {
            this.conversationId = conversationId;
            this.listener = listener;
        }
    }

    private static final CopyOnWriteArrayList<Registration> LISTENERS = new CopyOnWriteArrayList<>();

    /** 当前活着的实例。静态方法（clearUnread/currentState）都靠它。 */
    private static volatile TideService instance;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final Object lockSignal = new Object();
    private volatile boolean networkWake = false;
    private ConnectivityManager.NetworkCallback networkCallback;

    private volatile Thread worker;
    private volatile Session session;
    private volatile TideClient client;
    private volatile String conversationId;
    private volatile int state = STATE_OFFLINE;
    private final Map<String, Integer> unreadByConversation = new ConcurrentHashMap<>();
    /** 被 @ 时通知文案要用发送者名字；按会话记最近一次。 */
    private final Map<String, String> mentionByConversation = new ConcurrentHashMap<>();
    private final Map<String, String> titleByConversation = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeqByConversation = new ConcurrentHashMap<>();
    private final Map<String, Long> latestSeqByConversation = new ConcurrentHashMap<>();
    private final Map<String, Long> epochByConversation = new ConcurrentHashMap<>();
    private final Map<String, Long> lastReadSeqByConversation = new ConcurrentHashMap<>();
    private final Map<String, Long> suppressUnreadThroughByConversation = new ConcurrentHashMap<>();
    private final Map<String, Integer> notifiedUnreadByConversation = new ConcurrentHashMap<>();
    private final Map<String, String> previewByConversation = new ConcurrentHashMap<>();
    private final Set<String> catchUpInFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService catchUpExecutor = Executors.newFixedThreadPool(2);
    private final Object refreshThrottleLock = new Object();
    private volatile long lastConversationRefreshAt = 0L;
    private volatile String conversationTitle = null;
    private volatile long lastSeq = 0L;
    private volatile String realtimeMode = "legacy";
    private volatile long realtimeConfigAt = 0L;
    private volatile long legacyUntil = 0L;
    private volatile boolean hydratedForCurrentUnlock = false;
    /** 用户切了会话：旧 SSE 已断开，当前连接循环要立刻按新会话重连。 */
    private volatile boolean switchRequested = false;

    // ── 生命周期 ──────────────────────────────────────────────────────────

    @Override
    public IBinder onBind(Intent intent) {
        return null;   // 用静态监听而不是 bind，省掉 AIDL 与解绑时序
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        session = new Session(this);
        client = new TideClient(session);
        createChannel();
        registerNetworkCallback();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            cancelAllMessageNotifications();
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!LockManager.hasSessionKey()) {
            if (!running.get()) {
                try {
                    startForegroundCompat(buildNotification());
                } catch (RuntimeException e) {
                    stopSelf();
                    return START_NOT_STICKY;
                }
            } else {
                // 锁定时不要拆掉已有 SSE；只把常驻通知切到锁定态。
                updateNotification();
            }
            hydratedForCurrentUnlock = false;
            return START_STICKY;
        }

        wakeLockedWorker();
        String target = intent == null ? null : intent.getStringExtra(EXTRA_CONVERSATION_ID);
        String targetTitle = intent == null ? null : intent.getStringExtra(EXTRA_CONVERSATION_TITLE);
        if (target != null && !target.isEmpty()) {
            session.setConversationId(target);
            switchConversation(target, targetTitle);
        }

        if (!running.get()) {
            // 必须在 startForegroundService 后 5 秒内进前台，否则 ANR 崩溃。
            // 但 Android 12+ 对「从后台启动前台服务」有限制：系统用 START_STICKY 重启我们时
            // 通常算豁免，万一真被拦，宁可自己停下也不要抛出去崩掉。
            try {
                startForegroundCompat(buildNotification());
            } catch (RuntimeException e) {
                running.set(false);
                stopSelf();
                return START_NOT_STICKY;
            }
            running.set(true);
            worker = new Thread(this::connectLoop, "tide-sse");
            worker.start();
        }
        // START_STICKY：进程被杀后系统重建服务，重跑 onCreate → 读 Session 重连
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running.set(false);
        if (client != null) client.closeStream();
        Thread t = worker;
        if (t != null) t.interrupt();
        catchUpExecutor.shutdownNow();
        LISTENERS.clear();
        wakeLockedWorker();
        unregisterNetworkCallback();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    // ── 对外：Activity 用 ────────────────────────────────────────────────

    public static void start(Context ctx) {
        startConversation(ctx, null);
    }

    public static void startConversation(Context ctx, String conversationId) {
        startConversation(ctx, conversationId, null);
    }

    public static void startConversation(Context ctx, String conversationId, String title) {
        Intent i = new Intent(ctx, TideService.class).setAction(ACTION_START);
        if (conversationId != null && !conversationId.isEmpty()) {
            i.putExtra(EXTRA_CONVERSATION_ID, conversationId);
        }
        if (title != null && !title.isEmpty()) {
            i.putExtra(EXTRA_CONVERSATION_TITLE, title);
        }
        TideService svc = instance;
        if (svc != null) svc.wakeLockedWorker();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        TideService svc = instance;
        if (svc != null) {
            svc.cancelAllMessageNotifications();
            svc.stopForegroundCompat();
            svc.stopSelf();
            return;
        }
        try {
            ctx.stopService(new Intent(ctx, TideService.class));
            NotificationManager nm = (NotificationManager) ctx.getSystemService(NOTIFICATION_SERVICE);
            cancelMessageNotifications(nm);
        } catch (RuntimeException ignored) {
        }
    }

    /** 锁状态变化时只刷新常驻通知，不拆 SSE。 */
    public static void refreshNotification() {
        TideService svc = instance;
        if (svc != null) svc.updateNotification();
    }

    public static void refreshMessageNotifications() {
        TideService svc = instance;
        if (svc != null) svc.repostMessageNotifications();
    }

    public static void addListener(Listener l) {
        addListener(null, l);
    }

    public static void addListener(String conversationId, Listener l) {
        if (l == null) return;
        removeListener(l);
        LISTENERS.add(new Registration(conversationId, l));
    }

    public static void removeListener(Listener l) {
        LISTENERS.removeIf(registration -> registration.listener == l);
    }

    public static int currentState() {
        return stateSnapshot;
    }

    private static volatile int stateSnapshot = STATE_OFFLINE;

    /** Activity 绑定后先拿一次快照，避免错过绑定前的 lastSeq。 */
    public static long currentLastSeq() {
        TideService svc = instance;
        return svc == null ? 0L : svc.lastSeqFor(svc.conversationId);
    }

    public static long currentLastSeq(String conversationId) {
        TideService svc = instance;
        return svc == null ? 0L : svc.lastSeqFor(conversationId);
    }

    public static long currentLastReadSeq(String conversationId) {
        TideService svc = instance;
        if (svc == null || conversationId == null || conversationId.isEmpty()) return 0L;
        return svc.lastReadSeqByConversation.getOrDefault(conversationId, 0L);
    }

    /** 当前会话的兼容入口。 */
    public static void clearUnread() {
        TideService svc = instance;
        if (svc == null) return;
        svc.clearUnreadInternal(svc.conversationId);
    }

    /** 只清指定会话的未读与通知，打开 A 不能撤掉 B。 */
    public static void clearUnread(String conversationId) {
        TideService svc = instance;
        if (svc == null) return;
        svc.clearUnreadInternal(conversationId);
    }

    /** UI 解锁后立即唤醒可能停在锁态的重连循环，并恢复常驻通知。 */
    public static void onUiUnlocked() {
        TideService svc = instance;
        if (svc == null) return;
        svc.wakeLockedWorker();
        svc.updateNotification();
    }

    private void clearUnreadInternal(String cid) {
        if (cid == null || cid.isEmpty()) return;
        unreadByConversation.remove(cid);
        notifiedUnreadByConversation.remove(cid);
        previewByConversation.remove(cid);
        cancelMessageNotification(cid);
        updateNotification();
        dispatchUnreadChanged(cid);
    }

    private void waitForUnlockSignal() {
        synchronized (lockSignal) {
            if (LockManager.hasSessionKey() || !running.get()) return;
            try {
                lockSignal.wait(LOCKED_WAIT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void wakeLockedWorker() {
        synchronized (lockSignal) {
            lockSignal.notifyAll();
        }
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm == null) return;
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    onNetworkAvailable();
                }
            };
            cm.registerDefaultNetworkCallback(networkCallback);
        } catch (RuntimeException e) {
            android.util.Log.w("TideService", "network callback unavailable", e);
        }
    }

    private void unregisterNetworkCallback() {
        ConnectivityManager.NetworkCallback callback = networkCallback;
        networkCallback = null;
        if (callback == null) return;
        try {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null) cm.unregisterNetworkCallback(callback);
        } catch (RuntimeException ignored) {
        }
    }

    private void onNetworkAvailable() {
        if (!running.get()) return;
        if (!LockManager.hasSessionKey()) {
            refreshNotification();
            return;
        }
        networkWake = true;
        TideClient active = client;
        if (active != null) active.closeStream();
        Thread thread = worker;
        if (thread != null) thread.interrupt();
    }

    // ── 连接循环 ─────────────────────────────────────────────────────────

    private void connectLoop() {
        long backoff = RECONNECT_BASE_MS;
        int authRetries = 0;
        while (running.get()) {
            if (networkWake) {
                networkWake = false;
                Thread.interrupted();
                backoff = RECONNECT_BASE_MS;
            }
            if ("user".equals(resolveRealtimeMode())) {
                connectUserStreamLoop();
                continue;
            }
            final boolean[] sawReady = {false};
            switchRequested = false;
            try {
                if (!LockManager.hasSessionKey()) {
                    publish(STATE_LOCKED);
                    waitForUnlockSignal();
                    continue;
                }
                if (!session.hasLogin()) {
                    cancelAllMessageNotifications();
                    publish(STATE_FAILED);
                    stopSelf();
                    return;
                }
                String cid = ensureConversation();
                if (cid == null) {
                    publish(STATE_OFFLINE);
                    sleepQuietly(backoff);
                    backoff = Math.min(backoff * 2, RECONNECT_MAX_MS);
                    continue;
                }
                conversationId = cid;
                lastSeq = session.lastSeq(cid);

                publish(STATE_CONNECTING);
                final String cidFinal = cid;
                final long since = lastSeq;
                final String accessUsed = session.accessToken();
                final Throwable[] error = new Throwable[1];

                client.openStream(cidFinal, since, accessUsed, new TideClient.StreamListener() {
                    @Override
                    public void onReady(long latestSeq) {
                        if (!cidFinal.equals(conversationId)) {
                            client.closeStream();
                            return;
                        }
                        connected.set(true);
                        sawReady[0] = true;
                        publish(STATE_ONLINE);
                        latestSeqByConversation.put(cidFinal, latestSeq);
                        dispatchReady(cidFinal, latestSeq);
                    }

                    @Override
                    public void onMessage(String type, JSONObject message, long seq) {
                        if (!cidFinal.equals(conversationId)) return;
                        Message m = Message.fromJson(message);
                        if (seq > 0) {
                            lastSeq = seq;
                            lastSeqByConversation.put(cidFinal, seq);
                            latestSeqByConversation.merge(cidFinal, seq, Math::max);
                            session.setCursor(cidFinal, session.epoch(cidFinal), seq);
                        }
                        dispatchMessage(cidFinal, m);
                        if (!hasConversationListener(cidFinal)) onMessageWhileAway(cidFinal, m);
                    }

                    @Override
                    public void onTurn(boolean start, String reason) {
                        if (!cidFinal.equals(conversationId)) return;
                        dispatchTurn(cidFinal, start, reason);
                    }

                    @Override
                    public void onClosed(Throwable err) {
                        error[0] = err;
                    }
                });

                connected.set(false);
                if (!running.get()) break;
                if (!cidFinal.equals(conversationId)) {
                    switchRequested = false;
                    backoff = RECONNECT_BASE_MS;
                    authRetries = 0;
                    continue;
                }
                if (switchRequested) {
                    switchRequested = false;
                    backoff = RECONNECT_BASE_MS;
                    authRetries = 0;
                    continue;
                }
                if (sawReady[0]) {
                    backoff = RECONNECT_BASE_MS;   // 成功连上过就重置退避
                    authRetries = 0;
                }

                // 401：access 过期。必须走 refreshSessionIfStale()（它会写盘）；
                // 如果别的线程已经续过，会直接返回 OK，不会重复轮换 refresh token。
                if (isUnauthorized(error[0])) {
                    TideClient.RefreshOutcome outcome = client.refreshSessionIfStale(accessUsed);
                    switch (outcome) {
                        case OK:
                            authRetries++;
                            if (authRetries > MAX_STREAM_AUTH_RETRIES) {
                                // 续期成功但流持续 401：不是 token 过期，是结构性鉴权失败
                                cancelAllMessageNotifications();
                                publish(STATE_FAILED);
                                stopSelf();
                                return;
                            }
                            publish(STATE_CONNECTING);
                            continue;                    // token 已更新，立刻重连
                        case AUTH_FAILED:
                            // 凭证真死了，重试无意义
                            cancelAllMessageNotifications();
                            publish(STATE_FAILED);
                            stopSelf();
                            return;
                        case PROTOCOL:
                            // 网关不认这个请求（字段/版本不匹配），重试也是同一个 400
                            cancelAllMessageNotifications();
                            publish(STATE_PROTOCOL);
                            stopSelf();
                            return;
                        case TRANSIENT:
                        default:
                            // 断网 / 5xx：留在离线态继续退避，**绝不能当登录失效**
                            authRetries = 0;
                            break;
                    }
                } else {
                    authRetries = 0;
                }

                publish(STATE_OFFLINE);
                sleepQuietly(backoff);
                backoff = Math.min(backoff * 2, RECONNECT_MAX_MS);
            } catch (Exception e) {
                if (!running.get()) break;
                // request() 已按续期结果给出稳定 code；终态在这里收口，TRANSIENT 继续退避
                if (terminateIfAuthDead(e)) return;
                publish(STATE_OFFLINE);
                sleepQuietly(backoff);
                backoff = Math.min(backoff * 2, RECONNECT_MAX_MS);
            }
        }
    }

    private String resolveRealtimeMode() {
        long now = System.currentTimeMillis();
        if (now < legacyUntil) return "legacy";
        if (now - realtimeConfigAt < 5 * 60_000L) return realtimeMode;
        try {
            JSONObject config = client.realtimeConfig();
            String path = config.optString("streamPath", null);
            String mode = config.optString("mode", "legacy");
            realtimeMode = path != null && !path.isEmpty() && "user".equals(mode)
                    ? "user"
                    : "legacy";
            realtimeConfigAt = now;
            android.util.Log.i("TideSSE", "realtime mode=" + realtimeMode);
        } catch (Exception ignored) {
            if (realtimeConfigAt == 0L) realtimeMode = "legacy";
            realtimeConfigAt = now;
        }
        return realtimeMode;
    }

    private void hydrateCursorsFromSession() {
        lastSeqByConversation.clear();
        latestSeqByConversation.clear();
        epochByConversation.clear();
        for (Map.Entry<String, long[]> entry : session.allCursors().entrySet()) {
            long epoch = entry.getValue()[0];
            long seq = entry.getValue()[1];
            epochByConversation.put(entry.getKey(), epoch);
            lastSeqByConversation.put(entry.getKey(), seq);
            latestSeqByConversation.put(entry.getKey(), seq);
        }
        hydratedForCurrentUnlock = true;
    }

    private void connectUserStreamLoop() {
        long backoff = RECONNECT_BASE_MS;
        int authRetries = 0;
        while (running.get() && "user".equals(realtimeMode)) {
            if (networkWake) {
                networkWake = false;
                Thread.interrupted();
                backoff = RECONNECT_BASE_MS;
            }
            try {
                if (!LockManager.hasSessionKey()) {
                    publish(STATE_LOCKED);
                    waitForUnlockSignal();
                    continue;
                }
                if (!session.hasLogin()) {
                    cancelAllMessageNotifications();
                    publish(STATE_FAILED);
                    stopSelf();
                    return;
                }
                if (!hydratedForCurrentUnlock) hydrateCursorsFromSession();
                publish(STATE_CONNECTING);
                final String since = Uri.encode(
                        SinceCursor.build(session.allCursors(), conversationId)
                );
                final String accessUsed = session.accessToken();
                final Throwable[] error = new Throwable[1];
                final boolean[] sawReady = {false};
                client.openUserStream(since, accessUsed, new TideClient.UserStreamListener() {
                    @Override
                    public void onReady(JSONObject ready) {
                        connected.set(true);
                        sawReady[0] = true;
                        publish(STATE_ONLINE);
                        handleUserReady(ready);
                    }

                    @Override
                    public void onMessage(String cid, String type, JSONObject message,
                                          long epoch, long seq, String messageId) {
                        handleUserMessage(cid, message, epoch, seq);
                    }

                    @Override
                    public void onTurn(String cid, boolean start, String reason) {
                        if (cid != null && cid.equals(conversationId)) {
                            dispatchTurn(cid, start, reason);
                        }
                    }

                    @Override
                    public void onEvent(String cid, String type, JSONObject event) {
                        handleUserEvent(cid, type, event);
                    }

                    @Override
                    public void onClosed(Throwable err) {
                        error[0] = err;
                    }
                });

                connected.set(false);
                if (!running.get()) break;
                if (sawReady[0]) {
                    backoff = RECONNECT_BASE_MS;
                    authRetries = 0;
                }
                if (isUnauthorized(error[0])) {
                    TideClient.RefreshOutcome outcome = client.refreshSessionIfStale(accessUsed);
                    switch (outcome) {
                        case OK:
                            authRetries++;
                            if (authRetries > MAX_STREAM_AUTH_RETRIES) {
                                cancelAllMessageNotifications();
                                publish(STATE_FAILED);
                                stopSelf();
                                return;
                            }
                            publish(STATE_CONNECTING);
                            continue;
                        case AUTH_FAILED:
                            cancelAllMessageNotifications();
                            publish(STATE_FAILED);
                            stopSelf();
                            return;
                        case PROTOCOL:
                            cancelAllMessageNotifications();
                            publish(STATE_PROTOCOL);
                            stopSelf();
                            return;
                        case TRANSIENT:
                        default:
                            authRetries = 0;
                            break;
                    }
                } else if (shouldFallbackToLegacy(error[0])) {
                    fallbackToLegacy();
                    return;
                } else {
                    authRetries = 0;
                }
                publish(STATE_OFFLINE);
                sleepQuietly(backoff);
                backoff = Math.min(backoff * 2, RECONNECT_MAX_MS);
            } catch (Exception e) {
                if (!running.get()) break;
                if (shouldFallbackToLegacy(e)) {
                    fallbackToLegacy();
                    return;
                }
                publish(STATE_OFFLINE);
                sleepQuietly(backoff);
                backoff = Math.min(backoff * 2, RECONNECT_MAX_MS);
            }
        }
    }

    private boolean shouldFallbackToLegacy(Throwable error) {
        if (!(error instanceof TideClient.TideException)) return false;
        TideClient.TideException te = (TideClient.TideException) error;
        return RealtimeFallback.shouldUseLegacy(te.status);
    }

    private void fallbackToLegacy() {
        realtimeMode = "legacy";
        legacyUntil = System.currentTimeMillis() + 10 * 60_000L;
        realtimeConfigAt = System.currentTimeMillis();
        android.util.Log.w("TideSSE", "fallback to legacy for 10 minutes");
        client.closeStream();
    }

    private void handleUserReady(JSONObject ready) {
        JSONArray conversations = ready.optJSONArray("conversations");
        if (conversations == null) return;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < conversations.length(); i++) {
            JSONObject item = conversations.optJSONObject(i);
            if (item == null) continue;
            String cid = item.optString("conversationId", null);
            if (cid == null || cid.isEmpty()) continue;
            seen.add(cid);
            long serverEpoch = item.optLong("epoch", 1);
            long serverLatest = item.optLong("latestSeq", 0);
            long serverRead = item.optLong("lastReadSeq", 0);
            int unread = item.optInt("unread", 0);
            boolean replaySkipped = item.optBoolean("replaySkipped", false);
            boolean cursorAhead = item.optBoolean("cursorAhead", false);
            long localEpoch = epochByConversation.getOrDefault(cid, session.epoch(cid));
            long localSeq = lastSeqFor(cid);
            boolean hasCursor = lastSeqByConversation.containsKey(cid)
                    || session.lastSeq(cid) > 0;

            long chosenSeq = localSeq;
            if (!hasCursor) {
                session.setCursor(cid, serverEpoch, serverLatest);
                chosenSeq = serverLatest;
            } else if (serverEpoch > localEpoch) {
                chosenSeq = Math.min(localSeq, serverLatest);
                session.setCursor(cid, serverEpoch, chosenSeq);
                scheduleCatchUp(cid, true);
            } else if (serverEpoch == localEpoch) {
                if (serverLatest < localSeq || cursorAhead) {
                    session.resetCursor(cid, serverEpoch, serverLatest);
                    chosenSeq = serverLatest;
                    scheduleCatchUp(cid, true);
                } else if (serverLatest > localSeq || replaySkipped) {
                    scheduleCatchUp(cid, true);
                }
            } else {
                chosenSeq = Math.min(localSeq, serverLatest);
                session.resetCursor(cid, serverEpoch, chosenSeq);
                scheduleCatchUp(cid, true);
            }

            lastSeqByConversation.put(cid, chosenSeq);
            latestSeqByConversation.put(cid, serverLatest);
            epochByConversation.put(cid, serverEpoch);
            lastReadSeqByConversation.put(cid, serverRead);
            suppressUnreadThroughByConversation.put(cid, serverLatest);
            String title = item.optString("title", null);
            if (title != null && !title.isEmpty()) titleByConversation.put(cid, title);
            boolean activeUi = cid.equals(conversationId) && hasConversationListener(cid);
            unreadByConversation.put(cid, activeUi ? 0 : unread);
            if (activeUi || unread <= 0) {
                notifiedUnreadByConversation.remove(cid);
            } else {
                postUnreadNotificationIfIncreased(cid, unread);
            }
            dispatchUnreadChanged(cid);
        }
        for (String cid : new HashSet<>(lastSeqByConversation.keySet())) {
            if (seen.contains(cid)) continue;
            lastSeqByConversation.remove(cid);
            latestSeqByConversation.remove(cid);
            epochByConversation.remove(cid);
            lastReadSeqByConversation.remove(cid);
            suppressUnreadThroughByConversation.remove(cid);
            unreadByConversation.remove(cid);
            notifiedUnreadByConversation.remove(cid);
            previewByConversation.remove(cid);
            titleByConversation.remove(cid);
            session.removeCursor(cid);
            cancelMessageNotification(cid);
        }
        dispatchReady(conversationId, latestSeqFor(conversationId));
        updateNotification();
    }

    private void handleUserMessage(String cid, JSONObject message, long epoch, long seq) {
        if (cid == null || cid.isEmpty()) return;
        if (!epochByConversation.containsKey(cid)) refreshConversationsIfStale();
        long localEpoch = epochByConversation.getOrDefault(cid, session.epoch(cid));
        long localSeq = lastSeqFor(cid);
        if (epoch < localEpoch) return;
        boolean epochChanged = epoch > localEpoch;
        if (!epochChanged && seq <= localSeq) return;
        if (epochChanged) {
            epochByConversation.put(cid, epoch);
            suppressUnreadThroughByConversation.put(cid, 0L);
        }
        lastSeqByConversation.put(cid, seq);
        latestSeqByConversation.merge(cid, seq, Math::max);
        session.setCursor(cid, epoch, seq);
        if (epochChanged) scheduleCatchUp(cid, true);

        Message m = Message.fromJson(message);
        previewByConversation.put(cid, previewOf(m));
        String selfId = session.userId();
        if (selfId != null && !selfId.isEmpty() && m.mentions.contains(selfId)) {
            mentionByConversation.put(cid,
                    m.senderName == null || m.senderName.isEmpty() ? "有人" : m.senderName);
        }
        boolean activeUi = cid.equals(conversationId) && hasConversationListener(cid);
        boolean covered = seq <= suppressUnreadThroughByConversation.getOrDefault(cid, 0L);
        if (activeUi) {
            dispatchMessage(cid, m);
        } else if (shouldCountUnread(m) && !covered) {
            int count = NotificationState.increment(unreadByConversation, cid);
            updateNotification();
            postUnreadNotificationIfIncreased(cid, count);
            dispatchUnreadChanged(cid);
        }
    }

    private boolean shouldCountUnread(Message message) {
        if ("assistant".equals(message.role)) return true;
        if (!"user".equals(message.role)) return false;
        return message.senderUserId == null
                || !message.senderUserId.equals(session.userId());
    }

    private void handleUserEvent(String cid, String type, JSONObject event) {
        if (cid == null || cid.isEmpty()) return;
        if (!epochByConversation.containsKey(cid)) refreshConversationsIfStale();
        if ("conversation.rebuilt".equals(type)) {
            long newEpoch = event.optLong("epoch", epochByConversation.getOrDefault(cid, 1L));
            long localEpoch = epochByConversation.getOrDefault(cid, session.epoch(cid));
            if (newEpoch < localEpoch) return;
            long newSeq = event.optLong("seq", 0);
            long localSeq = lastSeqFor(cid);
            epochByConversation.put(cid, newEpoch);
            latestSeqByConversation.put(cid, newSeq);
            suppressUnreadThroughByConversation.put(cid, newSeq);
            if (newSeq >= localSeq) {
                session.setCursor(cid, newEpoch, localSeq);
                if (newSeq > localSeq) scheduleCatchUp(cid, false);
            } else {
                session.resetCursor(cid, newEpoch, newSeq);
                lastSeqByConversation.put(cid, newSeq);
                scheduleCatchUp(cid, true);
                if (cid.equals(conversationId)) dispatchReady(cid, latestSeqFor(cid));
            }
        } else if ("conversation.read".equals(type)) {
            long read = event.optLong("lastReadSeq", 0);
            long current = lastReadSeqByConversation.getOrDefault(cid, 0L);
            if (read <= current) return;
            lastReadSeqByConversation.put(cid, read);
            if (read >= latestSeqFor(cid)) {
                unreadByConversation.remove(cid);
                notifiedUnreadByConversation.remove(cid);
                mentionByConversation.remove(cid);
                cancelMessageNotification(cid);
            } else {
                refreshConversationsAsync();
            }
            updateNotification();
            dispatchUnreadChanged(cid);
        } else if ("conversation.created".equals(type)) {
            String title = event.optString("title", null);
            if (title != null && !title.isEmpty()) titleByConversation.put(cid, title);
            epochByConversation.put(cid, event.optLong("epoch", 1));
            latestSeqByConversation.put(cid, event.optLong("latestSeq", 0));
            refreshConversationsAsync();
            dispatchUnreadChanged(cid);
        } else if ("conversation.updated".equals(type)) {
            String title = event.optString("title", null);
            if (title != null && !title.isEmpty()) titleByConversation.put(cid, title);
            dispatchEvent(cid, type, event);
        } else if ("message.recalled".equals(type)) {
            dispatchEvent(cid, type, event);
        } else if ("conversation.deleted".equals(type)) {
            lastSeqByConversation.remove(cid);
            latestSeqByConversation.remove(cid);
            epochByConversation.remove(cid);
            lastReadSeqByConversation.remove(cid);
            suppressUnreadThroughByConversation.remove(cid);
            unreadByConversation.remove(cid);
            notifiedUnreadByConversation.remove(cid);
            mentionByConversation.remove(cid);
            previewByConversation.remove(cid);
            titleByConversation.remove(cid);
            session.removeCursor(cid);
            cancelMessageNotification(cid);
            if (cid.equals(conversationId)) dispatchReady(cid, 0L);
            updateNotification();
            dispatchUnreadChanged(cid);
            dispatchEvent(cid, type, event);
        }
    }

    private void refreshConversationsAsync() {
        if (catchUpExecutor.isShutdown()) return;
        catchUpExecutor.execute(() -> {
            try {
                JSONObject list = client.listConversations();
                JSONArray items = list.optJSONArray("conversations");
                if (items == null) return;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    String cid = item.optString("id", null);
                    if (cid == null || cid.isEmpty()) continue;
                    String title = item.optString("title", null);
                    if (title != null && !title.isEmpty()) titleByConversation.put(cid, title);
                    long epoch = item.optLong("epoch", 1);
                    long latest = item.optLong("latest_seq", item.optLong("latestSeq", 0));
                    epochByConversation.put(cid, epoch);
                    latestSeqByConversation.put(cid, latest);
                    long read = item.optLong("last_read_seq", item.optLong("lastReadSeq", 0));
                    lastReadSeqByConversation.put(cid, read);
                    boolean activeUi = cid.equals(conversationId) && hasConversationListener(cid);
                    int unread = activeUi ? 0 : item.optInt("unread", 0);
                    unreadByConversation.put(cid, unread);
                    if (unread <= 0) {
                        notifiedUnreadByConversation.remove(cid);
                    } else {
                        Integer previous = notifiedUnreadByConversation.get(cid);
                        if (previous != null && unread < previous) {
                            notifiedUnreadByConversation.put(cid, unread);
                        }
                    }
                    if (!lastSeqByConversation.containsKey(cid) && session.lastSeq(cid) == 0) {
                        session.setCursor(cid, epoch, latest);
                        lastSeqByConversation.put(cid, latest);
                    }
                }
                updateNotification();
            } catch (Exception ignored) {
            }
        });
    }

    private void refreshConversationsIfStale() {
        long now = SystemClock.elapsedRealtime();
        synchronized (refreshThrottleLock) {
            if (now - lastConversationRefreshAt < 5_000L) return;
            lastConversationRefreshAt = now;
        }
        refreshConversationsAsync();
    }

    private void scheduleCatchUp(String cid, boolean notifyUi) {
        if (cid == null || cid.isEmpty() || !catchUpInFlight.add(cid)) return;
        if (catchUpExecutor.isShutdown()) {
            catchUpInFlight.remove(cid);
            return;
        }
        catchUpExecutor.execute(() -> catchUp(cid, notifyUi));
    }

    private void catchUp(String cid, boolean notifyUi) {
        try {
            long after = lastSeqFor(cid);
            long epoch = epochByConversation.getOrDefault(cid, 1L);
            long latestWatermark = after;
            int total = 0;
            boolean hasMore = true;
            boolean gotMessages = false;
            while (hasMore && total < 2000) {
                JSONObject resp = client.listMessagesAfter(cid, after, 100);
                long serverEpoch = resp.optLong("epoch", epoch);
                long serverLatest = resp.optLong("latest_seq", resp.optLong("latestSeq", after));
                if (serverEpoch != epoch) {
                    epoch = serverEpoch;
                    if (serverLatest < after) after = serverLatest;
                    session.resetCursor(cid, epoch, after);
                }
                JSONArray messages = TideClient.messagesOf(resp);
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject message = messages.optJSONObject(i);
                    if (message == null) continue;
                    long seq = message.optLong("seq", 0);
                    if (seq > after) {
                        after = seq;
                        gotMessages = true;
                        lastSeqByConversation.put(cid, seq);
                        latestSeqByConversation.merge(cid, seq, Math::max);
                        session.setCursor(cid, epoch, seq);
                    }
                }
                latestWatermark = Math.max(latestWatermark, serverLatest);
                total += messages.length();
                if (messages.length() == 0) break;
                hasMore = resp.optBoolean("has_more", false);
            }
            if (hasMore) {
                long capped = Math.max(latestWatermark, after);
                lastSeqByConversation.put(cid, capped);
                latestSeqByConversation.put(cid, capped);
                session.resetCursor(cid, epoch, capped);
                dispatchHistoryTruncated(cid, capped, total);
                return;
            }
            if (latestWatermark > after) {
                after = latestWatermark;
                lastSeqByConversation.put(cid, after);
                latestSeqByConversation.put(cid, after);
                session.setCursor(cid, epoch, after);
            }
            refreshConversationsAsync();
            if (notifyUi && gotMessages && cid.equals(conversationId)) {
                dispatchReady(cid, latestSeqFor(cid));
            }
        } catch (Exception ignored) {
        } finally {
            catchUpInFlight.remove(cid);
        }
    }

    private void switchConversation(String conversationId, String title) {
        if (conversationId == null || conversationId.isEmpty()) return;
        boolean sameConversation = conversationId.equals(this.conversationId);
        String nextTitle = title == null || title.isEmpty() ? null : title;
        boolean titleChanged = nextTitle == null
                ? conversationTitle != null
                : !nextTitle.equals(conversationTitle);
        conversationTitle = nextTitle;
        if (sameConversation) {
            if (titleChanged) updateNotification();
            return;
        }
        this.conversationId = conversationId;
        if (nextTitle != null) titleByConversation.put(conversationId, nextTitle);
        dispatchReady(conversationId, latestSeqFor(conversationId));
        if (!"user".equals(realtimeMode)) {
            lastSeq = 0L;
            switchRequested = true;
            TideClient active = client;
            if (active != null) active.closeStream();
        }
        updateNotification();
    }

    /**
     * 判断异常是不是终态。返回 true 表示已 publish 终态并 stopSelf，调用方必须 return。
     *
     * request() 已经把续期结果映射成稳定 code：
     * refresh-auth-failed(401) / refresh-protocol(400) / refresh-transient(503)。
     * 这里不再二次 refresh，避免拿已经死掉的 refresh token 再轮换一次。
     */
    private boolean terminateIfAuthDead(Throwable error) {
        if (!(error instanceof TideClient.TideException)) return false;
        TideClient.TideException te = (TideClient.TideException) error;
        if ("refresh-protocol".equals(te.code) || "protocol-error".equals(te.code)) {
            cancelAllMessageNotifications();
            publish(STATE_PROTOCOL);
            stopSelf();
            return true;
        }
        if (te.status == 401) {
            cancelAllMessageNotifications();
            publish(STATE_FAILED);
            stopSelf();
            return true;
        }
        return false;
    }

    private String ensureConversation() throws IOException {
        String cid = session.conversationId();
        if (cid != null && !cid.isEmpty()) return cid;
        try {
            JSONObject list = client.listConversations();
            JSONArray items = list.optJSONArray("conversations");
            if (items == null) items = list.optJSONArray("items");
            if (items != null && items.length() > 0) {
                cid = items.getJSONObject(0).optString("id", null);
            }
            if (cid == null || cid.isEmpty()) {
                JSONObject created = client.createConversation("汐语");
                cid = created.optString("id", null);
            }
            if (cid != null && !cid.isEmpty()) session.setConversationId(cid);
            return cid;
        } catch (JSONException e) {
            throw new TideClient.TideException(400, "protocol-error", "解析会话响应失败");
        }
    }

    private static boolean isUnauthorized(Throwable t) {
        if (!(t instanceof TideClient.TideException)) return false;
        TideClient.TideException te = (TideClient.TideException) t;
        return te.status == 401;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── 状态与通知 ───────────────────────────────────────────────────────

    private void publish(int next) {
        if (state == next) return;
        state = next;
        stateSnapshot = next;
        updateNotification();
        int n = LISTENERS.size();
        for (int i = 0; i < n; i++) {
            try {
                LISTENERS.get(i).listener.onState(next);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void dispatchReady(String cid, long latestSeq) {
        if (cid == null) return;
        int n = LISTENERS.size();
        for (int i = 0; i < n; i++) {
            Registration registration = LISTENERS.get(i);
            if (registration.conversationId != null
                    && !registration.conversationId.equals(cid)) continue;
            try {
                registration.listener.onReady(latestSeq);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void dispatchMessage(String cid, Message m) {
        int n = LISTENERS.size();
        for (int i = 0; i < n; i++) {
            Registration registration = LISTENERS.get(i);
            if (registration.conversationId == null
                    || !registration.conversationId.equals(cid)) continue;
            try {
                registration.listener.onMessage(m);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void dispatchTurn(String cid, boolean start, String reason) {
        int n = LISTENERS.size();
        for (int i = 0; i < n; i++) {
            Registration registration = LISTENERS.get(i);
            if (registration.conversationId == null
                    || !registration.conversationId.equals(cid)) continue;
            try {
                registration.listener.onTurn(start, reason);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private boolean hasConversationListener(String cid) {
        for (Registration registration : LISTENERS) {
            if (registration.conversationId != null && registration.conversationId.equals(cid)) {
                return true;
            }
        }
        return false;
    }

    private void dispatchHistoryTruncated(String cid, long latestSeq, int loaded) {
        for (Registration registration : LISTENERS) {
            if (registration.conversationId != null && registration.conversationId.equals(cid)) {
                try {
                    registration.listener.onHistoryTruncated(cid, latestSeq, loaded);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private void dispatchUnreadChanged(String cid) {
        for (Registration registration : LISTENERS) {
            try {
                registration.listener.onUnreadChanged(cid);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void dispatchEvent(String cid, String type, JSONObject event) {
        if (cid == null) return;
        int n = LISTENERS.size();
        for (int i = 0; i < n; i++) {
            Registration registration = LISTENERS.get(i);
            if (registration.conversationId == null
                    || !registration.conversationId.equals(cid)) continue;
            try {
                registration.listener.onEvent(cid, type, event);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void postUnreadNotificationIfIncreased(String cid, int count) {
        if (!NotificationState.shouldNotify(notifiedUnreadByConversation, cid, count)) return;
        notifiedUnreadByConversation.put(cid, count);
        postMessageNotification(cid, count);
    }

    private void onMessageWhileAway(String cid, Message m) {
        if (!"assistant".equals(m.role)) return;   // 自己发的不用提醒
        if (cid == null || cid.isEmpty()) return;
        previewByConversation.put(cid, previewOf(m));
        int count = NotificationState.increment(unreadByConversation, cid);
        updateNotification();
        postUnreadNotificationIfIncreased(cid, count);
        dispatchUnreadChanged(cid);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.notif_channel_desc));
        ch.setShowBadge(true);
        ch.setSound(null, null);          // 常驻通知不响
        nm.createNotificationChannel(ch);

        NotificationChannel messages = new NotificationChannel(
                MESSAGE_CHANNEL_ID,
                getString(R.string.notif_message_channel),
                NotificationManager.IMPORTANCE_HIGH);
        messages.setDescription(getString(R.string.notif_message_channel_desc));
        messages.setShowBadge(true);
        messages.enableVibration(true);
        messages.setVibrationPattern(new long[]{0, 120, 80, 120});
        messages.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(messages);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, ChatActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (conversationId != null && !conversationId.isEmpty()) {
            open.putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId);
        }
        if (conversationTitle != null && !conversationTitle.isEmpty()) {
            open.putExtra(ChatActivity.EXTRA_TITLE, conversationTitle);
        }
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        String text;
        if (!LockManager.isUnlocked()) {
            text = getString(R.string.notif_locked);
        } else if (state == STATE_ONLINE) {
            int currentUnread = totalUnread();
            text = currentUnread > 0 ? currentUnread + " 条新消息" : getString(R.string.notif_online);
        } else {
            switch (state) {
                case STATE_CONNECTING:
                    text = getString(R.string.notif_connecting);
                    break;
                case STATE_FAILED:
                    text = getString(R.string.notif_failed);
                    break;
                case STATE_PROTOCOL:
                    text = getString(R.string.notif_protocol);
                    break;
                default:
                    text = getString(R.string.notif_offline);
            }
        }

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        String notificationTitle = !LockManager.isUnlocked()
                || conversationTitle == null
                || conversationTitle.isEmpty()
                ? getString(R.string.app_name)
                : conversationTitle;
        b.setContentTitle(notificationTitle)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        int currentNumber = totalUnread();
        if (currentNumber > 0) b.setNumber(currentNumber);
        return b.build();
    }

    private int unreadFor(String cid) {
        Integer value = cid == null ? null : unreadByConversation.get(cid);
        return value == null ? 0 : value;
    }

    private int totalUnread() {
        int total = 0;
        for (Integer value : unreadByConversation.values()) {
            total += value == null ? 0 : value;
        }
        return total;
    }

    private long lastSeqFor(String cid) {
        if (cid == null || cid.isEmpty()) return 0L;
        Long value = lastSeqByConversation.get(cid);
        return value != null ? value : session.lastSeq(cid);
    }

    private long latestSeqFor(String cid) {
        if (cid == null || cid.isEmpty()) return 0L;
        Long value = latestSeqByConversation.get(cid);
        return value != null ? value : lastSeqFor(cid);
    }

    private void updateNotification() {
        if (!running.get()) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.notify(NOTIFICATION_ID, buildNotification());
    }

    private void repostMessageNotifications() {
        for (Map.Entry<String, Integer> entry : unreadByConversation.entrySet()) {
            postMessageNotification(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 真正的消息提醒：正文默认不落地，锁屏/通知栏只显示「新消息」。
     * 与常驻前台通知分开，避免 IMPORTANCE_LOW 把提醒吞掉。
     */
    private void postMessageNotification(String cid, int count) {
        if (!running.get()) return;
        if (cid == null || cid.isEmpty()) return;
        if (ChatPrefs.isMuted(this, cid)) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.notify(NotificationState.messageNotificationId(cid), buildMessageNotification(cid, count));
    }

    private void cancelMessageNotification(String cid) {
        if (cid == null || cid.isEmpty()) return;
        notifiedUnreadByConversation.remove(cid);
        previewByConversation.remove(cid);
        mentionByConversation.remove(cid);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NotificationState.messageNotificationId(cid));
    }

    private void cancelAllMessageNotifications() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        cancelMessageNotifications(nm);
    }

    private static void cancelMessageNotifications(NotificationManager nm) {
        if (nm == null) return;
        try {
            for (StatusBarNotification item : nm.getActiveNotifications()) {
                int id = item.getId();
                if (id >= NotificationState.MESSAGE_NOTIFICATION_BASE
                        && id < NotificationState.MESSAGE_NOTIFICATION_BASE
                        + NotificationState.MESSAGE_NOTIFICATION_RANGE) {
                    nm.cancel(id);
                }
            }
        } catch (RuntimeException ignored) {
            // 通知服务异常时不影响停服
        }
    }

    private Notification buildMessageNotification(String cid, int count) {
        Intent open = new Intent(this, ChatActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (cid != null && !cid.isEmpty()) {
            open.putExtra(ChatActivity.EXTRA_CONVERSATION_ID, cid);
        }
        String title = cid == null ? null : titleByConversation.get(cid);
        if (title == null || title.isEmpty()) title = conversationTitle;
        if (title != null && !title.isEmpty()) {
            open.putExtra(ChatActivity.EXTRA_TITLE, title);
        }
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this,
                NotificationState.messageNotificationId(cid), open, flags);

        if (title == null || title.isEmpty()) title = getString(R.string.app_name);
        String mention = cid == null ? null : mentionByConversation.get(cid);
        String text = mention != null
                ? getString(R.string.notif_mention, mention)
                : (count > 1
                    ? getString(R.string.notif_new_messages, count)
                    : getString(R.string.notif_new_message));
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, MESSAGE_CHANNEL_ID)
                : new Notification.Builder(this);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE);
        }
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setWhen(System.currentTimeMillis())
                .setNumber(count);
        Notification.Builder publicBuilder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, MESSAGE_CHANNEL_ID)
                : new Notification.Builder(this);
        String preview = cid == null ? null : previewByConversation.get(cid);
        boolean showContent = NotificationPrefs.showContentOnLockscreen(this);
        publicBuilder.setSmallIcon(android.R.drawable.stat_notify_chat);
        if (showContent) {
            publicBuilder.setContentTitle(title)
                    .setContentText(preview == null || preview.isEmpty() ? text : preview);
        } else {
            publicBuilder.setContentTitle(getString(R.string.app_name))
                    .setContentText(getString(R.string.notif_new_message));
        }
        Notification publicVersion = publicBuilder.build();
        b.setPublicVersion(publicVersion);
        return b.build();
    }

    private static String previewOf(Message message) {
        if (message == null) return "";
        String text = message.text == null ? "" : message.text.trim();
        if (!text.isEmpty()) {
            return text.length() <= 80 ? text : text.substring(0, 80) + "…";
        }
        if (!message.attachments.isEmpty()) return "发来一个附件";
        return "";
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfoCompat.SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        running.set(false);
    }

    /** 只为了拿 FOREGROUND_SERVICE_TYPE_SPECIAL_USE，不去引 AndroidX 的 ServiceInfo。 */
    private static final class ServiceInfoCompat {
        static final int SPECIAL_USE = 1 << 30;   // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    }
}
