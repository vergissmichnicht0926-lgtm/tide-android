package com.whaleeye.tide;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 表情包库条目。 */
public final class Sticker {
    public final String id;
    public final String name;
    public final String attachmentId;
    public final String attachmentUrl;
    public final int useCount;
    public final String lastUsedAt;
    public final String disabledAt;

    public Sticker(String id, String name, String attachmentId, String attachmentUrl,
                   int useCount, String lastUsedAt, String disabledAt) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.attachmentId = attachmentId == null ? "" : attachmentId;
        this.attachmentUrl = attachmentUrl == null ? "" : attachmentUrl;
        this.useCount = Math.max(0, useCount);
        this.lastUsedAt = lastUsedAt == null || lastUsedAt.isEmpty() || "null".equals(lastUsedAt)
                ? null : lastUsedAt;
        this.disabledAt = disabledAt == null || disabledAt.isEmpty() || "null".equals(disabledAt)
                ? null : disabledAt;
    }

    public boolean isRecent() {
        return lastUsedAt != null;
    }

    public static Sticker fromJson(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("id", "");
        if (id.isEmpty()) return null;
        String attachmentId = o.optString("attachmentId", o.optString("attachment_id", ""));
        if (attachmentId.isEmpty()) return null;
        return new Sticker(
                id,
                o.optString("name", ""),
                attachmentId,
                o.optString("attachmentUrl", o.optString("attachment_url", "")),
                o.optInt("useCount", o.optInt("use_count", 0)),
                o.optString("lastUsedAt", o.optString("last_used_at", null)),
                o.optString("disabledAt", o.optString("disabled_at", null))
        );
    }

    /** 「最近」= 用过的；「全部」= 不过滤。顺序沿用服务端的 last_used/created 排序。 */
    public static List<Sticker> filter(List<Sticker> source, boolean recentOnly) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        if (!recentOnly) return Collections.unmodifiableList(new ArrayList<>(source));
        List<Sticker> result = new ArrayList<>();
        for (Sticker sticker : source) {
            if (sticker.isRecent()) result.add(sticker);
        }
        return result;
    }
}
