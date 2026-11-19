package com.whaleeye.tide;

/** 服务器地址规范化：没有 scheme 时补 https://，去掉尾部斜杠。 */
final class ServerAddress {
    private ServerAddress() {
    }

    static String normalize(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return "";
        if (!text.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            text = "https://" + text;
        }
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        return text;
    }
}
