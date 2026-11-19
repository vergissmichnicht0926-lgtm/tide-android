package com.whaleeye.tide;

/** 用户级流不可用时，哪些 HTTP 状态允许切回当前会话的 legacy SSE。 */
final class RealtimeFallback {
    private RealtimeFallback() {
    }

    static boolean shouldUseLegacy(int status) {
        return status == 404 || status == 409 || status == 426;
    }
}
