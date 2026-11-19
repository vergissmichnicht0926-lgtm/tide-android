package com.whaleeye.tide;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class UnreadDividerTest {
    private static Message message(long seq, boolean pending) {
        return new Message("m" + seq, "c" + seq, pending ? 0 : seq, "assistant",
                "text", 0, pending);
    }

    @Test
    public void findsFirstMessageAfterReadWatermark() {
        assertEquals(4, UnreadDivider.firstUnreadSeq(
                Arrays.asList(message(2, false), message(4, false), message(6, false)),
                3));
    }

    @Test
    public void noDividerWhenEverythingIsRead() {
        assertEquals(0, UnreadDivider.firstUnreadSeq(
                Arrays.asList(message(2, false), message(4, false)),
                4));
        assertEquals(0, UnreadDivider.firstUnreadSeq(Collections.emptyList(), 0));
    }

    @Test
    public void pendingRowsDoNotCreateDivider() {
        assertEquals(9, UnreadDivider.firstUnreadSeq(
                Arrays.asList(message(9, false), message(10, true)),
                8));
    }
}
