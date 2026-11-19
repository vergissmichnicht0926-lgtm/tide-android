package com.whaleeye.tide;

import java.util.Map;

/** 通知 id 与按会话未读的纯 Java 逻辑，方便 JVM 单测。 */
final class NotificationState {
    static final int MESSAGE_NOTIFICATION_BASE = 0x7102;
    static final int MESSAGE_NOTIFICATION_RANGE = 10_000;

    private NotificationState() {
    }

    static int messageNotificationId(String conversationId) {
        int hash = conversationId == null ? 0 : (conversationId.hashCode() & 0x7fffffff);
        return MESSAGE_NOTIFICATION_BASE + (hash % MESSAGE_NOTIFICATION_RANGE);
    }

    static int increment(Map<String, Integer> unread, String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) return 0;
        return unread.merge(conversationId, 1, Integer::sum);
    }

    static boolean shouldNotify(Map<String, Integer> notified, String conversationId, int count) {
        if (conversationId == null || conversationId.isEmpty() || count <= 0) return false;
        Integer previous = notified.get(conversationId);
        return previous == null || count > previous;
    }

    static void clear(Map<String, Integer> unread, Map<String, String> titles, String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) return;
        unread.remove(conversationId);
        if (titles != null) titles.remove(conversationId);
    }
}
