package com.whaleeye.tide;

/** UI 锁与凭据可用性的两条独立规则。 */
final class LockPolicy {
    private LockPolicy() {
    }

    static boolean canShowUi(boolean uiUnlocked, boolean keyReady) {
        return uiUnlocked && keyReady;
    }

    static boolean canUseCredentials(boolean keyReady) {
        return keyReady;
    }
}
