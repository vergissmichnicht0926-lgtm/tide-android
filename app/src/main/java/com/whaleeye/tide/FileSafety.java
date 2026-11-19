package com.whaleeye.tide;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/** 普通文件上传/预览共用的安全规则。保持纯 Java，便于 JVM 单测。 */
public final class FileSafety {
    public static final long MAX_UPLOAD_BYTES = 20L * 1024L * 1024L;
    public static final String DEFAULT_FILE_NAME = "attachment.bin";
    public static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    private static final int MAX_NAME_CHARS = 200;
    private static final Pattern MIME_PATTERN = Pattern.compile(
            "[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*"
    );

    private FileSafety() {
    }

    public static String sanitizeFileName(String raw) {
        if (raw == null) return DEFAULT_FILE_NAME;
        String value = raw.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        if (slash >= 0) value = value.substring(slash + 1);

        StringBuilder clean = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x20 && c != 0x7f && c != '/' && c != '\\') {
                clean.append(c);
            }
        }
        value = clean.toString().trim();
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        if (value.isEmpty() || ".".equals(value) || "..".equals(value)) {
            return DEFAULT_FILE_NAME;
        }
        return truncate(value, MAX_NAME_CHARS);
    }

    public static String normalizeMimeType(String raw) {
        String type = raw == null ? "" : raw.split(";", 2)[0].trim().toLowerCase(Locale.US);
        return MIME_PATTERN.matcher(type).matches() ? type : DEFAULT_MIME_TYPE;
    }

    /** 返回 root 下的直接子文件；先做名称清洗，再校验 canonical path。 */
    public static File childOf(File root, String name) throws IOException {
        File cleanRoot = root.getCanonicalFile();
        File candidate = new File(cleanRoot, sanitizeFileName(name)).getCanonicalFile();
        if (!isWithin(cleanRoot, candidate)) {
            throw new IOException("文件路径越界");
        }
        return candidate;
    }

    /** candidate 必须位于 root 内部；root 本身、`..` 和指向外部的 symlink 都不算。 */
    public static boolean isWithin(File root, File candidate) throws IOException {
        File cleanRoot = root.getCanonicalFile();
        File cleanCandidate = candidate.getCanonicalFile();
        if (cleanCandidate.equals(cleanRoot)) return false;
        return cleanCandidate.getPath().startsWith(cleanRoot.getPath() + File.separator);
    }

    public static String formatSize(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        return String.format(Locale.US, "%.1f MB", mb);
    }

    private static String truncate(String value, int maxChars) {
        if (value.length() <= maxChars) return value;
        int dot = value.lastIndexOf('.');
        String suffix = dot > 0 && value.length() - dot <= 20
                ? value.substring(dot)
                : "";
        int keep = Math.max(1, maxChars - suffix.length());
        return value.substring(0, keep) + suffix;
    }
}
