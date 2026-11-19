package com.whaleeye.tide;

import org.json.JSONObject;

public final class Attachment {
    public final String id;
    public final String name;
    public final String mimeType;
    public final long size;

    public Attachment(String id, String name, String mimeType, long size) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.mimeType = mimeType == null ? "" : mimeType;
        this.size = size;
    }

    public boolean isImage() {
        return mimeType.startsWith("image/");
    }

    public boolean isAudio() {
        return mimeType.startsWith("audio/");
    }

    public static Attachment fromJson(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("id", "");
        if (id.isEmpty()) return null;
        return new Attachment(
                id,
                o.optString("name", ""),
                o.optString("mimeType", o.optString("media_type", "")),
                o.optLong("size", o.optLong("bytes", 0))
        );
    }
}
