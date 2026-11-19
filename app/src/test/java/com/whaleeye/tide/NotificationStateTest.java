package com.whaleeye.tide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public final class NotificationStateTest {
    @Test
    public void messageIdStaysInMessageRangeAndIsStable() {
        String cid = "018a5f39-1ecd-41f3-87e4-e51ef8f09f2b";
        int id = NotificationState.messageNotificationId(cid);
        assertTrue(id >= NotificationState.MESSAGE_NOTIFICATION_BASE);
        assertTrue(id < NotificationState.MESSAGE_NOTIFICATION_BASE
                + NotificationState.MESSAGE_NOTIFICATION_RANGE);
        assertEquals(id, NotificationState.messageNotificationId(cid));
        assertEquals(NotificationState.MESSAGE_NOTIFICATION_BASE,
                NotificationState.messageNotificationId(null));
    }

    @Test
    public void incrementKeepsConversationsSeparate() {
        Map<String, Integer> unread = new HashMap<>();
        assertEquals(1, NotificationState.increment(unread, "A"));
        assertEquals(2, NotificationState.increment(unread, "A"));
        assertEquals(1, NotificationState.increment(unread, "B"));
        assertEquals(Integer.valueOf(2), unread.get("A"));
        assertEquals(Integer.valueOf(1), unread.get("B"));
    }

    @Test
    public void clearRemovesOnlyTargetConversationAndTitle() {
        Map<String, Integer> unread = new HashMap<>();
        Map<String, String> titles = new HashMap<>();
        unread.put("A", 2);
        unread.put("B", 1);
        titles.put("A", "私聊");
        titles.put("B", "群聊");

        NotificationState.clear(unread, titles, "A");
        assertFalse(unread.containsKey("A"));
        assertEquals(Integer.valueOf(1), unread.get("B"));
        assertFalse(titles.containsKey("A"));
        assertEquals("群聊", titles.get("B"));
    }

    @Test
    public void notifyOnlyWhenUnreadCountIncreases() {
        Map<String, Integer> notified = new HashMap<>();
        assertTrue(NotificationState.shouldNotify(notified, "A", 2));
        notified.put("A", 2);
        assertFalse(NotificationState.shouldNotify(notified, "A", 2));
        assertFalse(NotificationState.shouldNotify(notified, "A", 1));
        assertTrue(NotificationState.shouldNotify(notified, "A", 3));
        assertFalse(NotificationState.shouldNotify(notified, "A", 0));
    }

    @Test
    public void emptyConversationIsIgnored() {
        Map<String, Integer> unread = new HashMap<>();
        Map<String, String> titles = new HashMap<>();
        assertEquals(0, NotificationState.increment(unread, ""));
        NotificationState.clear(unread, titles, null);
        assertTrue(unread.isEmpty());
        assertTrue(titles.isEmpty());
    }
}
