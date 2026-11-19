package com.whaleeye.tide;

final class ResetCode {
    private ResetCode() {
    }

    static boolean isValid(String code) {
        return code != null && code.matches("\\d{8}");
    }
}
