package com.whaleeye.tide;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 网关 HTTP 客户端。零依赖，只用 HttpURLConnection 和 org.json。
 *
 * 两条路：
 *  - 普通请求：一次一调，返回 JSONObject 或抛 TideException
 *  - SSE：openStream 是阻塞的，调用方自己开线程，通过 listener 收事件
 */
public final class TideClient {

    /**
     * 续期结果。调用方必须按这一格来决定「还能不能救」，不要退化成 boolean。
     *
     * 四类分得开，是因为它们的处置完全不同：
     *  - OK          网络抖动过去了，立刻重连
     *  - AUTH_FAILED 凭证真死了（401），只能重新登录
     *  - PROTOCOL    网关不认这个请求（400/404），是版本或字段不匹配，重试一万次也没用
     *  - TRANSIENT   网络断了 / 5xx，服务器自己的问题，必须留在离线态继续退避
     */
    public enum RefreshOutcome {
        OK,
        AUTH_FAILED,
        PROTOCOL,
        TRANSIENT
    }

    /** 网关返回的业务错误。status 是 HTTP 状态码，code 是 body 里的 error.code。 */
    public static final class TideException extends IOException {
        public final int status;
        public final String code;

        TideException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public boolean isAuth() {
            return status == 401;
        }
    }

    /** SSE 回调。全部在调用线程（后台线程）上触发，改 UI 要自己 post 到主线程。 */
    public interface StreamListener {
        /** 连接建立、服务端发了 ready。 */
        void onReady(long latestSeq);

        /** 一条消息事件。type 是 user_message / assistant_message。 */
        void onMessage(String type, JSONObject message, long seq);

        /** turn 开始/结束。reason 只在 turn_end 上有。 */
        void onTurn(boolean start, String reason);

        /** 连接断了。客户端据此决定要不要重连。 */
        void onClosed(Throwable error);
    }

    /** 用户级 SSE 回调：一条连接承载多个 conversation。 */
    public interface UserStreamListener {
        void onReady(JSONObject ready);

        void onMessage(String conversationId, String type, JSONObject message,
                       long epoch, long seq, String messageId);

        void onTurn(String conversationId, boolean start, String reason);

        /** conversation.rebuilt / conversation.read / conversation.created / deleted */
        void onEvent(String conversationId, String type, JSONObject event);

        void onClosed(Throwable error);
    }

    private final Session session;
    private final String baseUrl;
    /** 当前正在读的 SSE 连接。切会话时由服务线程主动断开。 */
    private volatile HttpURLConnection activeStream;

    /**
     * 进程级续期锁。Activity 和 Service 各自 new TideClient，实例锁挡不住并发；
     * 两边共享同一个 SharedPreferences，所以锁也必须共享。
     */
    private static final Object REFRESH_LOCK = new Object();

    public TideClient(Session session) {
        this.session = session;
        this.baseUrl = session.baseUrl();
    }

    public String baseUrl() {
        return baseUrl;
    }

    /**
     * 登录前探活：GET /health，3 秒超时。
     * 只读，不写 Session；非 200 / 超时直接抛 IOException。
     */
    public static JSONObject probeHealth(String baseUrl) throws IOException {
        String body = probeHealthBody(baseUrl);
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new IOException("health 返回格式不正确", e);
        }
    }

    /** 只取 /health 的响应正文；拆出来便于 JVM 单测（不依赖 org.json）。 */
    static String probeHealthBody(String baseUrl) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(baseUrl + "/health").openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("Accept", "application/json");
            int status = conn.getResponseCode();
            // /health 在 db 异常时返回 503；读 body 才能区分「数据库异常」和「连不上」。
            if (status != 200 && status != 503) throw new IOException("health " + status);
            java.io.InputStream in = status == 200
                    ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) throw new IOException("health " + status);
            return readAll(in);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public void closeStream() {
        HttpURLConnection conn = activeStream;
        activeStream = null;
        if (conn != null) {
            try {
                conn.disconnect();
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ── 鉴权 ────────────────────────────────────────────────────────────

    public JSONObject deviceChallenge(String username, String keyFingerprint, String purpose)
            throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("username", username);
            body.put("key_fingerprint", keyFingerprint);
            body.put("purpose", purpose);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("POST", "/auth/device/challenge", body, false);
    }

    public JSONObject login(String username, String password, String deviceName,
                            JSONObject proof, String resetCode) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("username", username);
            body.put("password", password);
            body.put("device_name", deviceName);
            putProof(body, proof);
            if (resetCode != null && !resetCode.isEmpty()) body.put("reset_code", resetCode);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("POST", "/auth/login", body, false);
    }

    public JSONObject register(String code, String username, String password, String deviceName,
                               JSONObject proof) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("code", code);
            body.put("username", username);
            body.put("password", password);
            body.put("device_name", deviceName);
            putProof(body, proof);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("POST", "/auth/register", body, false);
    }

    public JSONObject enrollExistingDevice(String username, String password, String deviceName,
                                           JSONObject proof)
            throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("username", username);
            body.put("password", password);
            body.put("device_name", deviceName);
            putProof(body, proof);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("POST", "/auth/device/enroll-existing", body, true);
    }

    public JSONObject deviceApprovals() throws IOException {
        return request("GET", "/auth/device-approvals", null, true);
    }

    public JSONObject deviceApprovalStatus(String approvalId, String approvalToken)
            throws IOException {
        return request("GET", "/auth/device-approvals/" + approvalId + "/status?token="
                + java.net.URLEncoder.encode(approvalToken, "UTF-8"), null, false);
    }

    public JSONObject approveDevice(String approvalId, String verificationCode) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("verification_code", verificationCode);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("POST", "/auth/device-approvals/" + approvalId + "/approve",
                body, true);
    }

    public JSONObject rejectDevice(String approvalId) throws IOException {
        return request("POST", "/auth/device-approvals/" + approvalId + "/reject",
                new JSONObject(), true);
    }

    public JSONObject resetDeviceTrust(String userId) throws IOException {
        return request("POST", "/auth/users/" + userId + "/reset-devices",
                new JSONObject(), true);
    }

    private static void putProof(JSONObject body, JSONObject proof) throws JSONException {
        if (proof == null) return;
        body.put("device_public_key", proof.getString("device_public_key"));
        body.put("key_fingerprint", proof.getString("key_fingerprint"));
        body.put("challenge_id", proof.getString("challenge_id"));
        body.put("signature", proof.getString("signature"));
    }

    private JSONObject refresh() throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("refresh_token", session.refreshToken());
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("POST", "/auth/refresh", body, false);
    }

    // ── 会话 ────────────────────────────────────────────────────────────

    public JSONObject listConversations() throws IOException {
        return request("GET", "/conversations", null, true);
    }

    public JSONObject listStickers() throws IOException {
        return request("GET", "/stickers?scope=all", null, true);
    }

    public JSONObject realtimeConfig() throws IOException {
        return request("GET", "/realtime/config", null, true);
    }

    public JSONObject createConversation(String title) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("title", title);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("POST", "/conversations", body, true);
    }

    public JSONObject createGroup(String title) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("title", title);
            body.put("kind", "group");
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("POST", "/conversations", body, true);
    }

    public JSONObject listJoinableGroups() throws IOException {
        return request("GET", "/groups/joinable", null, true);
    }

    public JSONObject createGroupRequest(String conversationId) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("kind", "group");
            body.put("conversation_id", conversationId);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("POST", "/requests", body, true);
    }

    public JSONObject acceptGroupInvite(String code) throws IOException {
        return request("POST", "/invites/" + code + "/accept", new JSONObject(), true);
    }

    // ── 旅客申请与管理端 ────────────────────────────────────────────────

    public JSONObject createRequest(String kind) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("kind", kind);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("POST", "/requests", body, true);
    }

    public JSONObject myRequests() throws IOException {
        return request("GET", "/requests/mine", null, true);
    }

    public JSONObject adminUsers() throws IOException {
        return request("GET", "/admin/users", null, true);
    }

    public JSONObject adminInvites() throws IOException {
        return request("GET", "/admin/invites", null, true);
    }

    public JSONObject revokeInvite(String id) throws IOException {
        return request("POST", "/admin/invites/" + id + "/revoke", new JSONObject(), true);
    }

    public JSONObject createInvite(int expiresInMinutes, int maxUses) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("kind", "bot");
            body.put("role", "guest");
            body.put("expiresInMinutes", expiresInMinutes);
            body.put("maxUses", maxUses);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("POST", "/admin/invites", body, true);
    }

    public JSONObject createGroupInvite(String groupId, int expiresInMinutes, int maxUses)
            throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("kind", "group");
            body.put("groupId", groupId);
            body.put("expiresInMinutes", expiresInMinutes);
            body.put("maxUses", maxUses);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("POST", "/admin/invites", body, true);
    }

    public JSONObject adminGroups() throws IOException {
        return request("GET", "/admin/groups", null, true);
    }

    public JSONObject adminRequests() throws IOException {
        return request("GET", "/admin/requests", null, true);
    }

    public JSONObject approveRequest(String id) throws IOException {
        return request("POST", "/admin/requests/" + id + "/approve", new JSONObject(), true);
    }

    public JSONObject rejectRequest(String id) throws IOException {
        return request("POST", "/admin/requests/" + id + "/reject", new JSONObject(), true);
    }

    public JSONObject adminSettings() throws IOException {
        return request("GET", "/admin/settings", null, true);
    }

    public JSONObject updateAdminSettings(JSONObject patch) throws IOException {
        return request("PUT", "/admin/settings", patch, true);
    }

    public JSONObject listDevices() throws IOException {
        return request("GET", "/auth/devices", null, true);
    }

    public JSONObject revokeDevice(String deviceId) throws IOException {
        return request("POST", "/auth/devices/" + deviceId + "/revoke", new JSONObject(), true);
    }

    public JSONObject setPolicy(String conversationId, String policy) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("policy", policy);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("POST", "/conversations/" + conversationId + "/policy", body, true);
    }

    public JSONObject listMembers(String conversationId) throws IOException {
        return request("GET", "/conversations/" + conversationId + "/members", null, true);
    }

    public JSONObject removeMember(String conversationId, String userId) throws IOException {
        return request("DELETE", "/conversations/" + conversationId + "/members/" + userId,
                null, true);
    }

    public JSONObject me() throws IOException {
        return request("GET", "/auth/me", null, true);
    }

    public JSONObject botProfile() throws IOException {
        return request("GET", "/bot/profile", null, true);
    }

    public JSONObject updateMe(String displayName, String avatarDataUri) throws IOException {
        JSONObject body = new JSONObject();
        try {
            if (displayName != null) body.put("display_name", displayName);
            if (avatarDataUri != null) body.put("avatar", avatarDataUri);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("PATCH", "/auth/me", body, true);
    }

    public JSONObject memberProfile(String conversationId, String userId) throws IOException {
        return request("GET",
                "/conversations/" + conversationId + "/members/" + userId, null, true);
    }

    public byte[] downloadUserAvatar(String userId) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = open("GET", "/users/" + userId + "/avatar");
            String accessToken = session.accessToken();
            if (accessToken != null && !accessToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            }
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            int status = conn.getResponseCode();
            if (status != 200) throw readError(conn, status);
            return readBytes(conn.getInputStream());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public JSONObject listMoments(int limit) throws IOException {
        return request("GET", "/moments?limit=" + limit, null, true);
    }

    public JSONObject likeMoment(String momentId) throws IOException {
        return request("POST", "/moments/" + momentId + "/like", new JSONObject(), true);
    }

    public JSONObject unlikeMoment(String momentId) throws IOException {
        return request("DELETE", "/moments/" + momentId + "/like", null, true);
    }

    public JSONObject listMessages(String conversationId, long beforeSeq, int limit) throws IOException {
        String qs = "?limit=" + limit + (beforeSeq > 0 ? "&before=" + beforeSeq : "");
        return request("GET", "/conversations/" + conversationId + "/messages" + qs, null, true);
    }

    public JSONObject listMessagesAfter(String conversationId, long afterSeq, int limit)
            throws IOException {
        String qs = "?after_seq=" + afterSeq + "&limit=" + limit;
        return request("GET", "/conversations/" + conversationId + "/messages" + qs, null, true);
    }

    public JSONObject postRead(String conversationId, long throughSeq) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("through_seq", throughSeq);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("POST", "/conversations/" + conversationId + "/read", body, true);
    }

    /**
     * 发一条用户消息。client_msg_id 由调用方生成并在重试时复用——
     * 网关按它做幂等，同一个 id 重复提交不会造出两条消息。
     */
    public JSONObject postPrompt(String conversationId, String text, String clientMsgId) throws IOException {
        return postPrompt(conversationId, text, clientMsgId, null);
    }

    public JSONObject postPrompt(String conversationId, String text, String clientMsgId,
                                 JSONArray attachments) throws IOException {
        return postPrompt(conversationId, text, clientMsgId, attachments, null);
    }

    public JSONObject postPrompt(String conversationId, String text, String clientMsgId,
                                 JSONArray attachments, String replyToMessageId)
            throws IOException {
        return postPrompt(conversationId, text, clientMsgId, attachments,
                replyToMessageId, (JSONObject) null);
    }

    public JSONObject postPrompt(String conversationId, String text, String clientMsgId,
                                 JSONArray attachments, String replyToMessageId,
                                 long voiceDurationMs) throws IOException {
        JSONObject voice = null;
        if (voiceDurationMs > 0) {
            voice = new JSONObject();
            try {
                voice.put("durationMs", voiceDurationMs);
            } catch (JSONException e) {
                throw new IOException(e);
            }
        }
        return postPrompt(conversationId, text, clientMsgId, attachments,
                replyToMessageId, voice);
    }

    private JSONObject postPrompt(String conversationId, String text, String clientMsgId,
                                  JSONArray attachments, String replyToMessageId,
                                  JSONObject voice) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("text", text);
            body.put("client_msg_id", clientMsgId);
            if (attachments != null && attachments.length() > 0) {
                body.put("attachments", attachments);
            }
            if (replyToMessageId != null && !replyToMessageId.isEmpty()) {
                body.put("reply_to_message_id", replyToMessageId);
            }
            if (voice != null) body.put("voice", voice);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("POST", "/conversations/" + conversationId + "/prompt", body, true);
    }

    public JSONObject postSticker(String conversationId, String stickerId,
                                  String clientMsgId, String replyToMessageId) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("text", "");
            body.put("client_msg_id", clientMsgId);
            body.put("stickerId", stickerId);
            if (replyToMessageId != null && !replyToMessageId.isEmpty()) {
                body.put("reply_to_message_id", replyToMessageId);
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("POST", "/conversations/" + conversationId + "/prompt", body, true);
    }

    public JSONObject updateConversation(String conversationId, String title,
                                         String announcement) throws IOException {
        JSONObject body = new JSONObject();
        try {
            if (title != null) body.put("title", title);
            if (announcement != null) body.put("announcement", announcement);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("PATCH", "/conversations/" + conversationId, body, true);
    }

    public JSONObject setMemberRole(String conversationId, String userId, String role)
            throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("role", role);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return request("POST",
                "/conversations/" + conversationId + "/members/" + userId + "/role",
                body, true);
    }

    public JSONObject dissolveGroup(String conversationId) throws IOException {
        return request("DELETE", "/conversations/" + conversationId, null, true);
    }

    public JSONObject recallMessage(String conversationId, String messageId) throws IOException {
        return request("POST",
                "/conversations/" + conversationId + "/messages/" + messageId + "/recall",
                new JSONObject(), true);
    }

    public JSONObject searchMessages(String conversationId, String query, int limit)
            throws IOException {
        String encoded = java.net.URLEncoder.encode(query, "UTF-8");
        return request("GET",
                "/conversations/" + conversationId + "/messages?q=" + encoded + "&limit=" + limit,
                null, true);
    }

    public JSONObject messagesAround(String conversationId, String messageId, int limit)
            throws IOException {
        return request("GET",
                "/conversations/" + conversationId + "/messages?around="
                        + messageId + "&limit=" + limit,
                null, true);
    }

    public JSONObject uploadAttachment(String conversationId, byte[] data, String filename,
                                       String mediaType) throws IOException {
        return uploadAttachment(conversationId, data, filename, mediaType, false);
    }

    private JSONObject uploadAttachment(String conversationId, byte[] data, String filename,
                                        String mediaType, boolean retried) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = open("POST", "/conversations/" + conversationId + "/upload");
            String accessUsed = session.accessToken();
            if (accessUsed != null && !accessUsed.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + accessUsed);
            }
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            String safeName = FileSafety.sanitizeFileName(filename);
            String safeType = FileSafety.normalizeMimeType(mediaType);
            boolean multipart = !isAscii(safeName);
            byte[] head = null;
            byte[] tail = null;
            if (multipart) {
                String boundary = "----TideBoundary" + UUID.randomUUID().toString().replace("-", "");
                String partName = safeName.replace("\"", "_");
                head = ("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"file\"; filename=\""
                        + partName + "\"\r\n"
                        + "Content-Type: " + safeType + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8);
                tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                conn.setFixedLengthStreamingMode((long) head.length + data.length + tail.length);
            } else {
                conn.setRequestProperty("Content-Type", safeType);
                conn.setRequestProperty("X-File-Name", safeName);
                conn.setFixedLengthStreamingMode(data.length);
            }
            conn.setDoOutput(true);
            try (OutputStream out = conn.getOutputStream()) {
                if (multipart) {
                    out.write(head);
                    out.write(data);
                    out.write(tail);
                } else {
                    out.write(data);
                }
            }
            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                try {
                    return new JSONObject(readAll(conn.getInputStream()));
                } catch (JSONException e) {
                    throw new IOException("上传响应不是 JSON", e);
                }
            }
            TideException error = readError(conn, status);
            if (error.status == 401 && !retried) {
                RefreshOutcome outcome = refreshSessionIfStale(accessUsed);
                if (outcome == RefreshOutcome.OK) {
                    return uploadAttachment(conversationId, data, filename, mediaType, true);
                }
            }
            throw error;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7f) return false;
        }
        return true;
    }

    public byte[] downloadAttachment(String attachmentId) throws IOException {
        return downloadAttachment(attachmentId, false);
    }

    private byte[] downloadAttachment(String attachmentId, boolean retried) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = open("GET", "/attachments/" + attachmentId);
            String accessUsed = session.accessToken();
            if (accessUsed != null && !accessUsed.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + accessUsed);
            }
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                InputStream in = conn.getInputStream();
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                return out.toByteArray();
            }
            TideException error = readError(conn, status);
            if (error.status == 401 && !retried) {
                RefreshOutcome outcome = refreshSessionIfStale(accessUsed);
                if (outcome == RefreshOutcome.OK) return downloadAttachment(attachmentId, true);
            }
            throw error;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── SSE ────────────────────────────────────────────────────────────

    /**
     * 阻塞地读一条 SSE 流。sinceSeq>0 时服务端先从 DB 回放再订阅实时事件，
     * 所以断线重连只要把最后一条消息的 seq 带上就是无缝的。
     *
     * 返回时流已结束；异常通过 listener.onClosed 报，不往外抛，
     * 免得调用方在重连循环里还要套两层 catch。
     *
     * accessToken 必须由调用方传入，而且要和 401 后做 staleness 比较的值是同一个。
     * 不要在方法内部重新读 Session，否则会出现「送新 token、比旧快照」的窗口。
     */
    public void openStream(String conversationId, long sinceSeq, String accessToken, StreamListener listener) {
        String path = "/conversations/" + conversationId + "/stream"
                + (sinceSeq > 0 ? "?since_seq=" + sinceSeq : "");
        android.util.Log.i("TideSSE", "open " + logPath(path));
        openSse(path, conversationId, accessToken, new UserStreamListener() {
            @Override
            public void onReady(JSONObject ready) {
                listener.onReady(ready.optLong("latestSeq", ready.optLong("latest_seq", 0)));
            }

            @Override
            public void onMessage(String cid, String type, JSONObject message,
                                  long epoch, long seq, String messageId) {
                listener.onMessage(type, message, seq);
            }

            @Override
            public void onTurn(String cid, boolean start, String reason) {
                listener.onTurn(start, reason);
            }

            @Override
            public void onEvent(String cid, String type, JSONObject event) {
                // legacy per-conversation stream 不消费这些事件
            }

            @Override
            public void onClosed(Throwable error) {
                listener.onClosed(error);
            }
        });
    }

    /**
     * 用户级 SSE。since 由调用方用 Uri.encode 编好，格式 cid:epoch:seq,...。
     */
    public void openUserStream(String since, String accessToken, UserStreamListener listener) {
        String path = "/stream"
                + (since == null || since.isEmpty() ? "" : "?since=" + since);
        android.util.Log.i("TideSSE", "open " + logPath(path));
        openSse(path, null, accessToken, listener);
    }

    private static String logPath(String path) {
        return path.length() <= 1024 ? path : path.substring(0, 1024) + "...";
    }

    private void openSse(String path, String fallbackConversationId,
                         String accessToken, UserStreamListener listener) {
        HttpURLConnection conn = null;
        try {
            conn = open("GET", path);
            activeStream = conn;
            if (accessToken != null && !accessToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            }
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(0); // 长连接：不设读超时，靠心跳和主动 close
            int status = conn.getResponseCode();
            if (status != 200) {
                listener.onClosed(readError(conn, status));
                return;
            }
            InputStream in = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder data = new StringBuilder();
            String eventName = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        dispatchUser(data.toString(), eventName, fallbackConversationId, listener);
                        data.setLength(0);
                    }
                    eventName = null;
                    continue;
                }
                if (line.startsWith(":")) continue; // 注释/心跳
                if (line.startsWith("event:")) {
                    // 服务端把事件名写在这一行，同时也会在 data.type 里重复一次；
                    // 这里两个都收，哪边少一个都不会漏事件。
                    eventName = line.substring(6).trim();
                    continue;
                }
                if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                }
            }
            listener.onClosed(null);
        } catch (IOException e) {
            listener.onClosed(e);
        } finally {
            if (activeStream == conn) activeStream = null;
            if (conn != null) conn.disconnect();
        }
    }

    private void dispatchUser(String json, String eventName, String fallbackConversationId,
                              UserStreamListener listener) {
        try {
            JSONObject event = new JSONObject(json);
            String type = event.optString("type", "");
            if (type.isEmpty() && eventName != null) type = eventName;
            if ("ready".equals(type)) {
                listener.onReady(event);
            } else if ("user_message".equals(type) || "assistant_message".equals(type)) {
                JSONObject message = event.optJSONObject("message");
                if (message == null) message = event;
                String cid = event.optString("conversationId", fallbackConversationId);
                if (cid == null || cid.isEmpty()) cid = fallbackConversationId;
                long seq = event.optLong("seq", message.optLong("seq", 0));
                String messageId = event.optString("messageId", null);
                if (messageId == null || messageId.isEmpty()) {
                    messageId = message.optString("id", null);
                }
                listener.onMessage(
                        cid,
                        type,
                        message,
                        event.optLong("epoch", 1),
                        seq,
                        messageId);
            } else if ("turn_start".equals(type)) {
                listener.onTurn(event.optString("conversationId", fallbackConversationId),
                        true, null);
            } else if ("turn_end".equals(type)) {
                listener.onTurn(event.optString("conversationId", fallbackConversationId),
                        false, event.optString("reason", "completed"));
            } else if ("conversation.rebuilt".equals(type)
                    || "conversation.read".equals(type)
                    || "conversation.created".equals(type)
                    || "conversation.deleted".equals(type)
                    || "conversation.updated".equals(type)
                    || "message.recalled".equals(type)) {
                listener.onEvent(
                        event.optString("conversationId", fallbackConversationId),
                        type,
                        event);
            }
        } catch (JSONException e) {
            // 单条事件解析失败不能把整条流打断
        }
    }

    // ── 底层 ────────────────────────────────────────────────────────────

    private JSONObject request(String method, String path, JSONObject body, boolean withAuth) throws IOException {
        return request(method, path, body, withAuth, false);
    }

    private JSONObject request(String method, String path, JSONObject body, boolean withAuth, boolean retried)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = open(method, path);
            String accessUsed = null;
            if (withAuth) {
                accessUsed = session.accessToken();
                if (accessUsed != null && !accessUsed.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + accessUsed);
                }
            }
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            if (body != null) {
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setDoOutput(true);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                String text = readAll(conn.getInputStream());
                try {
                    return new JSONObject(text);
                } catch (JSONException e) {
                    throw new IOException("网关返回的不是 JSON：" + e.getMessage(), e);
                }
            }
            TideException e = readError(conn, status);
            // access 过期时自动换一次；换完仍 401 就收手，避免递归续期
            if (e.status == 401 && withAuth) {
                if (retried) throw new TideException(401, "refresh-auth-failed", e.getMessage());
                RefreshOutcome outcome = refreshSessionIfStale(accessUsed);
                switch (outcome) {
                    case OK:
                        return request(method, path, body, withAuth, true);
                    case AUTH_FAILED:
                        throw new TideException(401, "refresh-auth-failed", e.getMessage());
                    case PROTOCOL:
                        throw new TideException(400, "refresh-protocol", "客户端与网关协议不匹配，请更新 App");
                    case TRANSIENT:
                    default:
                        throw new TideException(503, "refresh-transient", "网络暂时不可用，请稍后重试");
                }
            }
            throw e;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 同一个字段服务端两种命名都要试一遍。
     *
     * 网关的响应是 camelCase（`accessToken` / `refreshToken`），但请求体里其他字段都是
     * snake_case，历史上真出过一次「发 snake、服务端只认 camel」导致续期恒定失败的故障，
     * 所以读写两头都别赌。
     */
    private static String firstNonNull(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, null);
            if (v != null && !v.isEmpty() && !"null".equals(v)) return v;
        }
        return null;
    }

    private static byte[] readBytes(InputStream input) throws IOException {
        try (InputStream in = input) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    /**
     * 续期并**立刻存盘**。`refresh()` 已经收成 private，公开入口只剩这里和
     * {@link #refreshSessionIfStale(String)}，两条都写盘。
     *
     * 曾经的 P0：SSE 那次 401 直接调了 `refresh()`（只发请求、返回 JSON、不写 Session），
     * 服务端在响应里轮换了 refresh token，新值被丢弃 —— 重连仍用旧 access，再 401 时
     * 拿旧 refresh 去换，服务端说「已被轮换」。于是从「15 分钟必死」变成「第一次 401 后必死」。
     *
     * **凡是调它，成功就已经把 token 写进 SharedPreferences 了**，调用方不需要也不许再自己 save。
     */
    public RefreshOutcome refreshSession() {
        synchronized (REFRESH_LOCK) {
            return refreshSessionLocked();
        }
    }

    /**
     * 只有 accessUsed 仍然是当前 access token 时才真的发续期请求。
     *
     * 并发场景：SSE 线程和发送线程同时 401，都拿着 A0。第一个线程续期成 A1/R1 后，
     * 第二个线程进锁时会发现当前 access 已经不是 A0，直接返回 OK，用 A1 重试即可。
     * 这样既不会拿同一个 refresh token 轮换两次，也不会把后一个线程误判成登录失效。
     */
    public RefreshOutcome refreshSessionIfStale(String accessUsed) {
        synchronized (REFRESH_LOCK) {
            String current = session.accessToken();
            if (accessUsed != null && !accessUsed.isEmpty()
                    && current != null && !current.isEmpty()
                    && !accessUsed.equals(current)) {
                return RefreshOutcome.OK;
            }
            return refreshSessionLocked();
        }
    }

    private RefreshOutcome refreshSessionLocked() {
        String refresh = session.refreshToken();
        if (refresh == null || refresh.isEmpty()) return RefreshOutcome.AUTH_FAILED;
        try {
            JSONObject r = refresh();
            String access = firstNonNull(r, "accessToken", "access_token");
            if (access == null) return RefreshOutcome.PROTOCOL;   // 200 但没给 token，契约坏了
            // 服务端可能不轮换 refresh token —— 这时必须留着旧的，
            // 把 null 写回去等于把唯一的续期凭证擦掉，下次 401 就彻底登不回来了。
            String newRefresh = firstNonNull(r, "refreshToken", "refresh_token");
            session.saveTokens(access, newRefresh != null ? newRefresh : refresh);
            return RefreshOutcome.OK;
        } catch (TideException e) {
            if (e.status == 401) return RefreshOutcome.AUTH_FAILED;
            if (e.status == 408 || e.status == 429) return RefreshOutcome.TRANSIENT;
            if (e.status >= 400 && e.status < 500) return RefreshOutcome.PROTOCOL;
            return RefreshOutcome.TRANSIENT;                      // 5xx：网关自己的问题
        } catch (IOException e) {
            return RefreshOutcome.TRANSIENT;                      // 断网、超时、DNS
        }
    }

    private HttpURLConnection open(String method, String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private TideException readError(HttpURLConnection conn, int status) {
        String code = "http-" + status;
        String message = "HTTP " + status;
        try {
            InputStream err = conn.getErrorStream();
            if (err != null) {
                JSONObject body = new JSONObject(readAll(err));
                JSONObject error = body.optJSONObject("error");
                if (error != null) {
                    code = error.optString("code", code);
                    message = error.optString("message", message);
                }
            }
        } catch (Exception ignored) {
            // 拿不到结构化错误就用状态码兜底
        }
        return new TideException(status, code, message);
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            int n;
            while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    /** 把 messages 数组从响应里取出来，顺便兼容 snake_case / camelCase 两种键名。 */
    public static JSONArray messagesOf(JSONObject resp) {
        JSONArray arr = resp.optJSONArray("messages");
        return arr == null ? new JSONArray() : arr;
    }
}
