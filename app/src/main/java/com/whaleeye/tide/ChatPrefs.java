package com.whaleeye.tide;

import android.content.Context;
import android.content.SharedPreferences;

/** 会话级本地偏好：置顶、免打扰。 */
final class ChatPrefs {
    private static final String PREFS = "tide.chat_prefs";

    private ChatPrefs() {
    }

    static boolean isPinned(Context context, String conversationId) {
        return prefs(context).getBoolean("pin." + conversationId, false);
    }

    static void setPinned(Context context, String conversationId, boolean value) {
        prefs(context).edit().putBoolean("pin." + conversationId, value).apply();
    }

    static boolean isMuted(Context context, String conversationId) {
        return prefs(context).getBoolean("mute." + conversationId, false);
    }

    static void setMuted(Context context, String conversationId, boolean value) {
        prefs(context).edit().putBoolean("mute." + conversationId, value).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
