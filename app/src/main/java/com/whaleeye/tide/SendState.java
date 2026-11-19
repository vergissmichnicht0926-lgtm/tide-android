package com.whaleeye.tide;

/** 自己发送的消息才显示三态；历史消息永远 NONE。 */
final class SendState {
    static final int NONE = 0;
    static final int SENDING = 1;
    static final int DELIVERED = 2;
    static final int FAILED = 3;

    private SendState() {
    }

    static int of(Message message, long now) {
        if (message == null || !message.isUser()) return NONE;
        if (message.pending) return SENDING;
        if (message.failed) return FAILED;
        if (message.showDeliveryStatus && now < message.statusUntil) return DELIVERED;
        return NONE;
    }
}
