package com.whaleeye.tide;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把 Session 的 per-conversation 游标编码成 /stream?since= 的精确列表。
 * 格式：cid:epoch:seq,cid:epoch:seq；跳过 seq<=0，最多 100 条，active 会话优先。
 */
public final class SinceCursor {
    static final int MAX_ENTRIES = 100;
    static final int MAX_CHARS = 8000;

    private SinceCursor() {
    }

    public static String build(Map<String, long[]> cursors) {
        return build(cursors, null);
    }

    public static String build(Map<String, long[]> cursors, String activeConversationId) {
        if (cursors == null || cursors.isEmpty()) return "";
        List<Map.Entry<String, long[]>> entries = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : cursors.entrySet()) {
            long[] value = entry.getValue();
            if (value == null || value.length < 2 || value[1] <= 0) continue;
            entries.add(entry);
        }
        entries.sort((a, b) -> {
            boolean aActive = activeConversationId != null
                    && activeConversationId.equals(a.getKey());
            boolean bActive = activeConversationId != null
                    && activeConversationId.equals(b.getKey());
            if (aActive != bActive) return aActive ? -1 : 1;
            long seqA = a.getValue()[1];
            long seqB = b.getValue()[1];
            if (seqA != seqB) return Long.compare(seqB, seqA);
            return a.getKey().compareTo(b.getKey());
        });
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, long[]> entry : entries) {
            if (count >= MAX_ENTRIES) break;
            long[] value = entry.getValue();
            String part = entry.getKey() + ":" + value[0] + ":" + value[1];
            int nextLength = out.length() + (out.length() > 0 ? 1 : 0) + part.length();
            if (nextLength > MAX_CHARS) break;
            if (out.length() > 0) out.append(',');
            out.append(part);
            count += 1;
        }
        return out.toString();
    }
}
