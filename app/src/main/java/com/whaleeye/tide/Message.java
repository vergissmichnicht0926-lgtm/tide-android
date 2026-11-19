package com.whaleeye.tide;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一条消息。seq 是网关给的单调序号，客户端拿它做断线补齐（since_seq）。
 *
 * pending=true 表示本地已显示、还没收到服务端回执（发送失败或还在路上）。
 */
public final class Message {
    public final String id;
    /** 用户消息的本地幂等键；助手消息为 null。 */
    public final String clientMsgId;
    public final long seq;
    public final String role;
    public final String text;
    public final long createdAt;
    public final List<Attachment> attachments;
    public final String senderName;
    public final String senderUserId;
    /** 被撤回：正文与附件都不再显示。 */
    public final boolean recalled;
    public final String replyToId;
    public final String replyToName;
    public final String replyToText;
    public final boolean replyToRecalled;
    public final List<String> mentions;
    /** >0 表示语音消息，单位毫秒。 */
    public final long voiceDurationMs;
    /** 表情消息：content_json.sticker.id；普通消息为 null。 */
    public final String stickerId;
    public final String stickerName;
    public boolean pending;
    public boolean failed;
    public boolean showDeliveryStatus;
    public long statusUntil;
    /** 绑定视图时按当前用户计算，不参与相等性。 */
    public boolean mentionedMe;

    public Message(String id, long seq, String role, String text, long createdAt, boolean pending) {
        this(id, pending ? id : null, seq, role, text, createdAt, pending);
    }

    public Message(String id, String clientMsgId, long seq, String role, String text,
                   long createdAt, boolean pending) {
        this(id, clientMsgId, seq, role, text, createdAt, pending, null);
    }

    public Message(String id, String clientMsgId, long seq, String role, String text,
                   long createdAt, boolean pending, List<Attachment> attachments) {
        this(id, clientMsgId, seq, role, text, createdAt, pending, attachments,
                null, null, false, null, null, null, false, null, 0, null, null);
    }

    public Message(String id, String clientMsgId, long seq, String role, String text,
                   long createdAt, boolean pending, List<Attachment> attachments,
                   long voiceDurationMs) {
        this(id, clientMsgId, seq, role, text, createdAt, pending, attachments,
                null, null, false, null, null, null, false, null, voiceDurationMs, null, null);
    }

    private Message(String id, String clientMsgId, long seq, String role, String text,
                    long createdAt, boolean pending, List<Attachment> attachments,
                    String senderName, String senderUserId, boolean recalled,
                    String replyToId, String replyToName, String replyToText,
                    boolean replyToRecalled, List<String> mentions, long voiceDurationMs,
                    String stickerId, String stickerName) {
        this.id = id;
        this.clientMsgId = clientMsgId;
        this.seq = seq;
        this.role = role;
        this.text = text;
        this.createdAt = createdAt;
        this.pending = pending;
        this.attachments = attachments == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(attachments));
        this.senderName = senderName;
        this.senderUserId = senderUserId;
        this.recalled = recalled;
        this.replyToId = replyToId;
        this.replyToName = replyToName;
        this.replyToText = replyToText;
        this.replyToRecalled = replyToRecalled;
        this.mentions = mentions == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(mentions));
        this.voiceDurationMs = Math.max(0L, voiceDurationMs);
        this.stickerId = stickerId == null || stickerId.isEmpty() ? null : stickerId;
        this.stickerName = stickerName == null || stickerName.isEmpty() ? null : stickerName;
    }

    public boolean isUser() {
        return "user".equals(role);
    }

    public boolean isSticker() {
        return stickerId != null;
    }

    /** 本地乐观表情气泡。 */
    public static Message sticker(String id, String clientMsgId, long seq, String role,
                                  String stickerId, String stickerName,
                                  List<Attachment> attachments, long createdAt,
                                  boolean pending, String replyToId) {
        return new Message(id, clientMsgId, seq, role, "", createdAt, pending, attachments,
                null, null, false, replyToId, null, null, false, null, 0,
                stickerId, stickerName);
    }

    /** 收到撤回事件后替换成一条不可再操作的消息。 */
    public Message asRecalled() {
        return new Message(id, clientMsgId, seq, role, "", createdAt, pending,
                attachments, senderName, senderUserId, true,
                replyToId, replyToName, replyToText, replyToRecalled, mentions, 0,
                null, null);
    }

    public static Message fromJson(JSONObject o) {
        JSONObject content = o.optJSONObject("content");
        String text = "";
        if (content != null) {
            text = content.optString("text", "");
        } else {
            text = o.optString("text", "");
        }
        String clientMsgId = o.optString("clientMsgId", null);
        if (clientMsgId == null || clientMsgId.isEmpty() || "null".equals(clientMsgId)) {
            clientMsgId = o.optString("client_msg_id", null);
        }
        if (clientMsgId != null && (clientMsgId.isEmpty() || "null".equals(clientMsgId))) {
            clientMsgId = null;
        }
        List<Attachment> attachments = new ArrayList<>();
        org.json.JSONArray array = o.optJSONArray("attachments");
        if (array == null && content != null) array = content.optJSONArray("attachments");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                Attachment attachment = Attachment.fromJson(array.optJSONObject(i));
                if (attachment != null) attachments.add(attachment);
            }
        }
        String senderName = o.optString("senderName", null);
        if (senderName != null && (senderName.isEmpty() || "null".equals(senderName))) {
            senderName = null;
        }
        String senderUserId = o.optString("senderUserId", null);
        if (senderUserId != null && (senderUserId.isEmpty() || "null".equals(senderUserId))) {
            senderUserId = null;
        }
        boolean recalled = o.optBoolean("recalled", false);
        JSONObject replyTo = o.optJSONObject("replyTo");
        String replyToId = replyTo == null ? null : replyTo.optString("id", null);
        String replyToName = replyTo == null ? null : replyTo.optString("senderName", null);
        String replyToText = replyTo == null ? null : replyTo.optString("text", "");
        boolean replyToRecalled = replyTo != null && replyTo.optBoolean("recalled", false);
        if (replyToId == null || replyToId.isEmpty() || "null".equals(replyToId)) {
            replyToId = o.optString("replyToMessageId", null);
            if (replyToId == null && content != null) {
                replyToId = content.optString("replyToMessageId", null);
            }
            if (replyToId != null && (replyToId.isEmpty() || "null".equals(replyToId))) {
                replyToId = null;
            }
        }
        List<String> mentions = new ArrayList<>();
        org.json.JSONArray mentionArray = o.optJSONArray("mentions");
        if (mentionArray != null) {
            for (int i = 0; i < mentionArray.length(); i++) {
                String value = mentionArray.optString(i, "");
                if (!value.isEmpty()) mentions.add(value);
            }
        }
        JSONObject voice = o.optJSONObject("voice");
        if (voice == null && content != null) voice = content.optJSONObject("voice");
        long voiceDurationMs = voice == null ? 0L : voice.optLong("durationMs", 0L);
        JSONObject sticker = o.optJSONObject("sticker");
        if (sticker == null && content != null) sticker = content.optJSONObject("sticker");
        String stickerId = sticker == null ? null : sticker.optString("id", null);
        String stickerName = sticker == null ? null : sticker.optString("name", null);
        long createdAt = o.optLong("created_at", 0L);
        if (createdAt <= 0) {
            Object raw = o.opt("createdAt");
            if (raw instanceof Number) {
                createdAt = ((Number) raw).longValue();
            } else if (raw instanceof String) {
                createdAt = Moment.parseIso((String) raw);
            }
        }
        return new Message(
                o.optString("id", ""),
                clientMsgId,
                o.optLong("seq", 0),
                o.optString("role", "assistant"),
                text,
                createdAt,
                false,
                attachments,
                senderName,
                senderUserId,
                recalled,
                replyToId,
                replyToName,
                replyToText,
                replyToRecalled,
                mentions,
                voiceDurationMs,
                stickerId,
                stickerName
        );
    }
}
