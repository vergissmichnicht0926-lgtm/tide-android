package com.whaleeye.tide;

/** 引导第 2 屏的字段规则；返回 0 表示可以提交。 */
final class OnboardRules {
    private OnboardRules() {
    }

    static int errorFor(String invite, String account, String password) {
        if (isBlank(invite)) {
            return R.string.onboard_error_invite;
        }
        if (isBlank(account)) {
            return R.string.onboard_error_account;
        }
        if (password == null || password.length() < 8) {
            return R.string.onboard_error_password;
        }
        return 0;
    }

    /**
     * 邀请码清洗：去掉首尾空白与引号、去掉「邀请码：」「code:」这类前缀、去掉中间空格。
     * 大小写保持原样（旧码区分大小写，服务端只对新格式做大小写回退）。
     */
    static String normalizeInvite(String raw) {
        if (raw == null) {
            return "";
        }
        String value = foldFullWidth(raw).trim();
        value = stripEdgeNoise(value);
        // 前缀：冒号可有可无，但必须有冒号或空白做分隔，避免吃掉以 code 开头的旧码。
        value = value.replaceAll(
                "(?i)^(邀请码是|邀请码|invite\\s*code|invite|code)(?:\\s*[:：]\\s*|\\s+)", "");
        value = stripEdgeNoise(value);
        value = value.replaceAll("\\s+", "");
        return value;
    }

    private static String stripEdgeNoise(String value) {
        return value.replaceAll("^[\\s\"'“”‘’]+", "")
                .replaceAll("[\\s\"'“”‘’]+$", "");
    }

    /** 全角 ASCII（！到 ～）折成半角，全角空格折成普通空格。 */
    private static String foldFullWidth(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\u3000') {
                out.append(' ');
            } else if (c >= '\uFF01' && c <= '\uFF5E') {
                out.append((char) (c - 0xFEE0));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
