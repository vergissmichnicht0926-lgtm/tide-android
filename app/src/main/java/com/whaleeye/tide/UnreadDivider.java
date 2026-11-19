package com.whaleeye.tide;

import java.util.List;

/** 找“第一条未读”的分割位置。 */
final class UnreadDivider {
    private UnreadDivider() {
    }

    static long firstUnreadSeq(List<Message> messages, long lastReadSeq) {
        if (messages == null || messages.isEmpty()) return 0L;
        for (Message message : messages) {
            if (message == null || message.pending || message.seq <= 0) continue;
            if (message.seq > lastReadSeq) return message.seq;
        }
        return 0L;
    }
}
