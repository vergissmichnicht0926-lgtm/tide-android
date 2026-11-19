package com.whaleeye.tide;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** 动态列表的一行。 */
public final class Moment {
    public final String id;
    public final String text;
    public final String authorName;
    public final long createdAt;
    public int likes;
    public boolean liked;

    Moment(String id, String text, String authorName, long createdAt,
           int likes, boolean liked) {
        this.id = id;
        this.text = text;
        this.authorName = authorName;
        this.createdAt = createdAt;
        this.likes = likes;
        this.liked = liked;
    }

    public static Moment fromJson(JSONObject object) {
        JSONObject author = object.optJSONObject("author");
        String name = author == null ? "" : author.optString("name", "");
        return new Moment(
                object.optString("id", ""),
                object.optString("text", ""),
                TextUtils.isEmpty(name) ? "汐语" : name,
                parseIso(object.optString("createdAt", "")),
                object.optInt("likes", 0),
                object.optBoolean("likedByMe", false)
        );
    }

    public static List<Moment> listFrom(JSONObject response) {
        List<Moment> items = new ArrayList<>();
        JSONArray array = response.optJSONArray("moments");
        if (array == null) return items;
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object != null) items.add(fromJson(object));
        }
        return items;
    }

    static long parseIso(String value) {
        if (TextUtils.isEmpty(value)) return 0L;
        try {
            String trimmed = value.length() >= 19 ? value.substring(0, 19) : value;
            SimpleDateFormat input = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            input.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = input.parse(trimmed);
            return date == null ? 0L : date.getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 微信式时间：刚刚 / N 分钟前 / N 小时前 / 昨天 HH:mm / M月d日 HH:mm。 */
    public static String timeLabel(long at, long now) {
        if (at <= 0) return "";
        long diff = Math.max(0L, now - at);
        if (diff < 60_000L) return "刚刚";
        if (diff < 3_600_000L) return (diff / 60_000L) + " 分钟前";

        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(at);
        Calendar today = Calendar.getInstance();
        today.setTimeInMillis(now);
        boolean sameDay = then.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);
        if (sameDay) return (diff / 3_600_000L) + " 小时前";

        Calendar yesterday = (Calendar) today.clone();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        String clock = new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(at));
        if (yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return "昨天 " + clock;
        }
        if (then.get(Calendar.YEAR) == today.get(Calendar.YEAR)) {
            return new SimpleDateFormat("M月d日 ", Locale.CHINA).format(new Date(at)) + clock;
        }
        return new SimpleDateFormat("yyyy年M月d日 ", Locale.CHINA).format(new Date(at)) + clock;
    }
}
