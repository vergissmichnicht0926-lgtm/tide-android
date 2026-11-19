package com.whaleeye.tide;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CursorPolicyTest {
    @Test
    public void epochUpgradeAllowsReset() {
        assertArrayEquals(new long[]{2, 5}, CursorPolicy.next(1, 99, 2, 5));
    }

    @Test
    public void sameEpochForwardOnly() {
        assertArrayEquals(new long[]{1, 10}, CursorPolicy.next(1, 9, 1, 10));
    }

    @Test
    public void sameEpochBackwardIgnored() {
        assertNull(CursorPolicy.next(1, 10, 1, 9));
    }

    @Test
    public void oldEpochIgnored() {
        assertNull(CursorPolicy.next(2, 10, 1, 99));
    }
}
