package com.whaleeye.tide;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** `tide://join` 的纯 Java 解析器，便于 JVM 单测。 */
public final class JoinLink {
    public final String server;
    public final String code;
    public final String kind;
    public final String groupId;

    private JoinLink(String server, String code, String kind, String groupId) {
        this.server = server;
        this.code = code;
        this.kind = kind;
        this.groupId = groupId;
    }

    public static JoinLink parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("邀请链接为空");
        }
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("邀请链接格式不正确", e);
        }
        if (!"tide".equalsIgnoreCase(uri.getScheme())
                || !"join".equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException("不是汐语邀请链接");
        }
        Map<String, String> query = parseQuery(uri.getRawQuery());
        String server = normalizeServer(query.get("server"));
        String code = trimLimit(query.get("code"), 200);
        if (code.isEmpty()) throw new IllegalArgumentException("邀请链接缺少邀请码");
        String kind = trimLimit(query.get("kind"), 20).toLowerCase(Locale.US);
        if (kind.isEmpty()) kind = "bot";
        if (!"bot".equals(kind) && !"group".equals(kind)) {
            throw new IllegalArgumentException("邀请类型不正确");
        }
        String groupId = trimLimit(query.get("group"), 200);
        return new JoinLink(server, code, kind, groupId);
    }

    /**
     * 从任意粘贴文本里认出邀请：支持 tide://join?… 和 https://host/tide/j/CODE?k=…&g=…。
     * 认不出来返回 null（调用方按普通邀请码处理）。
     */
    public static JoinLink fromAnyText(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.length() > 800) return null;
        int tideAt = text.indexOf("tide://join");
        if (tideAt >= 0) {
            String tail = text.substring(tideAt);
            int stop = tail.length();
            for (int i = 0; i < tail.length(); i++) {
                char c = tail.charAt(i);
                if (c == '"' || c == '<' || c == '>' || Character.isWhitespace(c)) {
                    stop = i;
                    break;
                }
            }
            try {
                return parse(tail.substring(0, stop));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        int jAt = text.indexOf("/j/");
        int schemeAt = jAt > 0 ? text.lastIndexOf("https://", jAt) : -1;
        if (jAt > 0 && schemeAt >= 0) {
            String server = text.substring(schemeAt, jAt);
            String rest = text.substring(jAt + 3);
            int cut = rest.length();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '?' || c == '#' || Character.isWhitespace(c)) {
                    cut = i;
                    break;
                }
            }
            String code = rest.substring(0, cut);
            if (code.isEmpty()) return null;
            String query = rest.substring(cut);
            int ws = -1;
            for (int i = 0; i < query.length(); i++) {
                if (Character.isWhitespace(query.charAt(i))) {
                    ws = i;
                    break;
                }
            }
            if (ws >= 0) query = query.substring(0, ws);
            if (query.startsWith("?")) query = query.substring(1);
            if (query.endsWith("#")) query = query.substring(0, query.length() - 1);
            Map<String, String> params = parseQuery(query);
            try {
                String normalized = normalizeServer(server);
                String kind = params.get("k");
                kind = kind == null ? "bot" : kind.toLowerCase(Locale.US);
                if (!"group".equals(kind)) kind = "bot";
                return new JoinLink(normalized, code, kind, params.get("g"));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /** 看起来像邀请链接（哪怕解析失败），用来区分「邀请码无效」和「链接不完整」。 */
    public static boolean looksLikeInviteLink(String raw) {
        if (raw == null) return false;
        String text = raw.trim();
        return text.contains("tide://join") || text.contains("/j/");
    }

    private static String normalizeServer(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.isEmpty()) throw new IllegalArgumentException("邀请链接缺少服务器地址");
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("服务器地址格式不正确", e);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getHost().isEmpty()
                || uri.getRawUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("邀请链接里的服务器必须是 HTTPS 地址");
        }
        if (value.length() > 500) throw new IllegalArgumentException("服务器地址过长");
        return value;
    }

    private static String trimLimit(String value, int max) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > max) throw new IllegalArgumentException("链接参数过长");
        return clean;
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String part : raw.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String key = eq < 0 ? part : part.substring(0, eq);
            String value = eq < 0 ? "" : part.substring(eq + 1);
            try {
                key = URLDecoder.decode(key, StandardCharsets.UTF_8.name());
                value = URLDecoder.decode(value, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                throw new IllegalArgumentException("邀请链接参数编码不正确", e);
            }
            result.put(key, value);
        }
        return result;
    }
}
