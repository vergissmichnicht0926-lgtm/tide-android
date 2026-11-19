package com.whaleeye.tide;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RealtimeFallbackTest {
    @Test
    public void onlyDisabledOrMismatchedEndpointsFallBack() {
        assertTrue(RealtimeFallback.shouldUseLegacy(404));
        assertTrue(RealtimeFallback.shouldUseLegacy(409));
        assertTrue(RealtimeFallback.shouldUseLegacy(426));
    }

    @Test
    public void authBadRequestAndServerErrorsStayOnUserStream() {
        assertFalse(RealtimeFallback.shouldUseLegacy(400));
        assertFalse(RealtimeFallback.shouldUseLegacy(401));
        assertFalse(RealtimeFallback.shouldUseLegacy(403));
        assertFalse(RealtimeFallback.shouldUseLegacy(500));
        assertFalse(RealtimeFallback.shouldUseLegacy(503));
    }
}
