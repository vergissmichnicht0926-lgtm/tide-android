package com.whaleeye.tide;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LockPolicyTest {
    @Test
    public void uiLockKeepsCredentialsForService() {
        assertFalse(LockPolicy.canShowUi(false, true));
        assertTrue(LockPolicy.canUseCredentials(true));
    }

    @Test
    public void hardLockRemovesCredentials() {
        assertFalse(LockPolicy.canShowUi(false, false));
        assertFalse(LockPolicy.canUseCredentials(false));
    }

    @Test
    public void unlockedUiRequiresBothStates() {
        assertTrue(LockPolicy.canShowUi(true, true));
        assertFalse(LockPolicy.canShowUi(true, false));
    }
}
