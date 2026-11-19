package com.whaleeye.tide;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;

/**
 * 本地凭据与连接设置。
 *
 * 只存 JWT 和网关地址，不存密码。refresh token 单独存，换 access 时不覆盖。
 * 以后上应用锁（10.14 五项）时，这里要加一个「解锁前不读」的开关。
 */
public final class Session {
    static final String PREFS = "tide.session";
    private static final String K_BASE = "base";
    private static final String K_ACCESS = "access";
    private static final String K_REFRESH = "refresh";
    private static final String K_DEVICE = "device";
    private static final String K_USER = "user";
    private static final String K_USER_ID = "user_id";
    private static final String K_ROLE = "role";
    private static final String K_NAME = "display_name";
    private static final String K_CONV = "conversation";
    private static final String K_SEQ = "last_seq";
    private static final String K_SEQ_PREFIX = "last_seq.";
    private static final String K_EPOCH_PREFIX = "epoch.";
    private static final String K_DEVICE_TRUST = "device_trust_enrolled";
    /** 开源版不内置任何服务器；地址来自管理员登录页或邀请链接。 */
    private static final String DEFAULT_BASE = "";

    private final SharedPreferences prefs;

    public Session(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String baseUrl() {
        String v = prefs.getString(K_BASE, null);
        if (v == null || v.isEmpty()) {
            String fallback = BuildConfig.TIDE_SERVER == null || BuildConfig.TIDE_SERVER.isEmpty()
                    ? DEFAULT_BASE
                    : BuildConfig.TIDE_SERVER;
            return ServerAddress.normalize(fallback);
        }
        return ServerAddress.normalize(v);
    }

    public void setBaseUrl(String v) {
        String normalized = ServerAddress.normalize(v);
        if (normalized.isEmpty()) return;
        prefs.edit().putString(K_BASE, normalized).apply();
    }

    public String accessToken() { return secureGet(K_ACCESS); }
    public String refreshToken() { return secureGet(K_REFRESH); }
    public String deviceId() { return secureGet(K_DEVICE); }
    public String username() { return secureGet(K_USER); }
    public String userId() { return secureGet(K_USER_ID); }
    public String role() {
        String value = secureGet(K_ROLE);
        return value == null ? "guest" : value;
    }
    public String displayName() {
        String value = secureGet(K_NAME);
        return value == null ? username() : value;
    }
    public boolean isAdmin() { return "admin".equals(role()); }
    public boolean deviceTrustEnrolled() { return prefs.getBoolean(K_DEVICE_TRUST, false); }

    public void setDeviceTrustEnrolled(boolean enrolled) {
        prefs.edit().putBoolean(K_DEVICE_TRUST, enrolled).apply();
    }
    public String conversationId() { return secureGet(K_CONV); }

    public void setConversationId(String id) {
        if (!LockManager.hasSessionKey()) return;
        String previous = conversationId();
        SharedPreferences.Editor editor = prefs.edit().putString(K_CONV, encrypt(id));
        // M7b 起一个账号会切 direct / group。旧版只有一个全局水位，
        // 第一次升级时把它归到原会话，避免切群后把旧 seq 当成新群的 since_seq。
        if (previous != null && !previous.equals(id) && !prefs.contains(K_SEQ_PREFIX + previous)) {
            long legacy = prefs.getLong(K_SEQ, 0L);
            if (legacy > 0) editor.putLong(K_SEQ_PREFIX + previous, legacy);
            editor.remove(K_SEQ);
        }
        editor.apply();
    }

    /** SSE 断线重连用的水位：最后一条已收消息的 seq。 */
    public long lastSeq() {
        return lastSeq(conversationId());
    }

    public long lastSeq(String conversationId) {
        if (!LockManager.hasSessionKey()) return 0L;
        String cid = conversationId;
        return cid == null ? 0L : prefs.getLong(K_SEQ_PREFIX + cid, 0L);
    }

    public void setLastSeq(long seq) {
        setLastSeq(conversationId(), seq);
    }

    public void setLastSeq(String conversationId, long seq) {
        setCursor(conversationId, epoch(conversationId), seq);
    }

    public long epoch(String conversationId) {
        if (!LockManager.hasSessionKey()) return 0L;
        String cid = conversationId;
        return cid == null ? 0L : prefs.getLong(K_EPOCH_PREFIX + cid, 1L);
    }

    /**
     * 同 epoch 只前进；epoch 变化时允许 seq 回退/重置。
     */
    public void setCursor(String conversationId, long epoch, long seq) {
        if (!LockManager.hasSessionKey()) return;
        String cid = conversationId;
        if (cid == null || cid.isEmpty()) return;
        long currentEpoch = epoch(cid);
        String epochKey = K_EPOCH_PREFIX + cid;
        String seqKey = K_SEQ_PREFIX + cid;
        long[] next = CursorPolicy.next(currentEpoch, prefs.getLong(seqKey, 0L), epoch, seq);
        if (next == null) return;
        prefs.edit()
                .putLong(epochKey, next[0])
                .putLong(seqKey, next[1])
                .apply();
    }

    /** 服务端游标权威场景：强制写精确值，不做单调保护。 */
    public void resetCursor(String conversationId, long epoch, long seq) {
        if (!LockManager.hasSessionKey()) return;
        String cid = conversationId;
        if (cid == null || cid.isEmpty()) return;
        prefs.edit()
                .putLong(K_EPOCH_PREFIX + cid, epoch)
                .putLong(K_SEQ_PREFIX + cid, Math.max(0L, seq))
                .apply();
    }

    public Map<String, long[]> allCursors() {
        Map<String, long[]> result = new HashMap<>();
        if (!LockManager.hasSessionKey()) return result;
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(K_SEQ_PREFIX)) continue;
            String cid = key.substring(K_SEQ_PREFIX.length());
            long seq = prefs.getLong(key, 0L);
            long epoch = prefs.getLong(K_EPOCH_PREFIX + cid, 1L);
            result.put(cid, new long[]{epoch, seq});
        }
        return result;
    }

    public void removeCursor(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) return;
        prefs.edit()
                .remove(K_SEQ_PREFIX + conversationId)
                .remove(K_EPOCH_PREFIX + conversationId)
                .apply();
    }

    public boolean hasLogin() {
        if (!LockManager.hasSessionKey()) return false;
        String token = accessToken();
        return token != null && !token.isEmpty();
    }

    public void saveLogin(String username, String access, String refresh, String deviceId) {
        prefs.edit()
                .putString(K_USER, encrypt(username))
                .putString(K_ACCESS, encrypt(access))
                .putString(K_REFRESH, encrypt(refresh))
                .putString(K_DEVICE, encrypt(deviceId))
                .apply();
    }

    public void saveUserId(String userId) {
        if (userId == null) return;
        prefs.edit().putString(K_USER_ID, encrypt(userId)).apply();
    }

    public void saveIdentity(String role, String displayName) {
        prefs.edit()
                .putString(K_ROLE, encrypt(role == null || role.isEmpty() ? "guest" : role))
                .putString(K_NAME, encrypt(displayName))
                .apply();
    }

    public void saveTokens(String access, String refresh) {
        SharedPreferences.Editor e = prefs.edit().putString(K_ACCESS, encrypt(access));
        if (refresh != null) e.putString(K_REFRESH, encrypt(refresh));
        // 轮换后的 refresh token 必须同步落盘：apply() 还在队列里时进程被杀，
        // 下次启动就会拿旧 token 去换，服务端已经把它轮换掉了。
        e.commit();
    }

    public void clearLogin() {
        // 只清凭据和会话绑定，保留 base url。
        // 会话 id 与水位必须一起清：换个账号登进来，旧的 conversation id 指向别人的对话，
        // 旧的 last_seq 又会让 SSE 的 since_seq 跳过新账号的真实消息。
        SharedPreferences.Editor editor = prefs.edit()
                .remove(K_ACCESS)
                .remove(K_REFRESH)
                .remove(K_DEVICE)
                .remove(K_USER)
                .remove(K_USER_ID)
                .remove(K_ROLE)
                .remove(K_NAME)
                .remove(K_CONV)
                .remove(K_DEVICE_TRUST)
                .remove(K_SEQ);
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(K_SEQ_PREFIX) || key.startsWith(K_EPOCH_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.commit();
    }

    /** Upgrade installs: encrypt every legacy plaintext credential before routing. */
    public void migrateSecrets() {
        if (!LockManager.hasSessionKey()) return;
        secureGet(K_ACCESS);
        secureGet(K_REFRESH);
        secureGet(K_DEVICE);
        secureGet(K_USER);
        secureGet(K_ROLE);
        secureGet(K_NAME);
        secureGet(K_CONV);
    }

    private String secureGet(String key) {
        if (!LockManager.hasSessionKey()) return null;
        String value = prefs.getString(key, null);
        if (value == null) return null;
        if (SecureStore.isEncrypted(value)) {
            try {
                return SecureStore.decrypt(value);
            } catch (GeneralSecurityException e) {
                return null;
            }
        }
        // Upgrade installs: migrate the old plaintext value on first unlocked read.
        try {
            prefs.edit().putString(key, SecureStore.encrypt(value)).apply();
        } catch (GeneralSecurityException ignored) {
        }
        return value;
    }

    private static String encrypt(String value) {
        if (value == null) return null;
        if (!LockManager.hasSessionKey()) {
            throw new IllegalStateException("应用锁未解锁，拒绝写入凭据");
        }
        try {
            return SecureStore.encrypt(value);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("凭据加密失败", e);
        }
    }

}
