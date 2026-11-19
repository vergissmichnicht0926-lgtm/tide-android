package com.whaleeye.tide;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ResetCodeTest {
    @Test
    public void acceptsEightDigits() {
        assertTrue(ResetCode.isValid("12345678"));
    }

    @Test
    public void rejectsWrongLengthOrNonDigits() {
        assertFalse(ResetCode.isValid(null));
        assertFalse(ResetCode.isValid(""));
        assertFalse(ResetCode.isValid("1234567"));
        assertFalse(ResetCode.isValid("123456789"));
        assertFalse(ResetCode.isValid("1234abcd"));
    }
}
