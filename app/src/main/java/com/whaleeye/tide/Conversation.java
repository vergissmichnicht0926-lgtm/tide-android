package com.whaleeye.tide;

import android.text.TextUtils;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** 会话列表的一行。 */
public final class Conversation {
    public final String id;
    public final String kind;
    public final String title;
    public final String summary;
    public final String announcement;
    public final String updatedAt;
    public final int unread;
    public final boolean rebuildPending;

    private Conversation(String id, String kind, String title, String summary,
                         String announcement, String updatedAt, int unread,
                         boolean rebuildPending) {
        this.id = id;
        this.kind = kind;
        this.title = title;
        this.summary = summary;
        this.announcement = announcement;
        this.updatedAt = updatedAt;
        this.unread = unread;
        this.rebuildPending = rebuildPending;
    }

    public static Conversation fromJson(JSONObject object) {
        String kind = object.optString("kind", "direct");
        String title = object.optString("title", "");
        String announcement = object.optString("announcement", null);
        if (announcement != null && (announcement.isEmpty() || "null".equals(announcement))) {
            announcement = null;
        }
        if (TextUtils.isEmpty(title) || "null".equals(title)) {
            title = "direct".equals(kind) ? "汐语" : "群聊";
        }
        return new Conversation(
                object.optString("id", ""),
                kind,
                title,
                object.optString("summary", ""),
                announcement,
                object.optString("updatedAt", ""),
                object.optInt("unread", 0),
                object.optBoolean("rebuild_pending", false)
        );
    }

    public boolean isGroup() {
        return "group".equals(kind);
    }

    public String initial() {
        String value = displayTitle();
        return TextUtils.isEmpty(value) ? "汐" : value.substring(0, 1);
    }

    public String displayTitle() {
        return isGroup() ? title : "汐语";
    }

    public String preview() {
        if (rebuildPending) return "会话重建中…";
        return TextUtils.isEmpty(summary) || "null".equals(summary)
                ? "还没有消息"
                : summary;
    }

    public String timeLabel() {
        if (TextUtils.isEmpty(updatedAt)) return "";
        try {
            String value = updatedAt.length() >= 19
                    ? updatedAt.substring(0, 19) : updatedAt;
            SimpleDateFormat input = new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            input.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = input.parse(value);
            if (date == null) return "";
            Calendar now = Calendar.getInstance();
            Calendar then = Calendar.getInstance();
            then.setTime(date);
            if (sameDay(now, then)) {
                SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.CHINA);
                return clock.format(date);
            }
            Calendar yesterday = (Calendar) now.clone();
            yesterday.add(Calendar.DAY_OF_YEAR, -1);
            if (sameDay(yesterday, then)) return "昨天";
            long days = (now.getTimeInMillis() - then.getTimeInMillis()) / 86400000L;
            if (days >= 0 && days < 7) {
                String[] weekdays = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
                return weekdays[then.get(Calendar.DAY_OF_WEEK) - 1];
            }
            SimpleDateFormat dateFormat = new SimpleDateFormat("MM-dd", Locale.CHINA);
            return dateFormat.format(date);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }
}
