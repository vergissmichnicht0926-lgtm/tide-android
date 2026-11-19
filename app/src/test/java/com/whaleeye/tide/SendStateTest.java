package com.whaleeye.tide;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SendStateTest {
    private static Message message() {
        return new Message("m1", "c1", 1, "user", "hi", 0, false);
    }

    @Test
    public void pendingIsSending() {
        Message message = message();
        message.pending = true;
        assertEquals(SendState.SENDING, SendState.of(message, 100));
    }

    @Test
    public void failureWins() {
        Message message = message();
        message.failed = true;
        assertEquals(SendState.FAILED, SendState.of(message, 100));
    }

    @Test
    public void deliveredFadesAfterThreeSeconds() {
        Message message = message();
        message.showDeliveryStatus = true;
        message.statusUntil = 3_100;
        assertEquals(SendState.DELIVERED, SendState.of(message, 3_099));
        assertEquals(SendState.NONE, SendState.of(message, 3_101));
    }

    @Test
    public void historyAndIncomingMessagesDoNotShowStatus() {
        assertEquals(SendState.NONE, SendState.of(message(), 100));
        Message assistant = new Message("m2", "c2", 2, "assistant", "yo", 0, false);
        assertEquals(SendState.NONE, SendState.of(assistant, 100));
    }
}
