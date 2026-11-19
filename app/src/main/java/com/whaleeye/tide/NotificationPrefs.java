package com.whaleeye.tide;

import android.content.Context;

/** 锁屏通知内容是否允许展示；默认关闭。 */
final class NotificationPrefs {
    private static final String PREFS = "tide.notifications";
    private static final String K_SHOW_LOCKSCREEN_CONTENT = "show_lockscreen_content";

    private NotificationPrefs() {
    }

    static boolean showContentOnLockscreen(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(K_SHOW_LOCKSCREEN_CONTENT, true);
    }

    static void setShowContentOnLockscreen(Context context, boolean enabled) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(K_SHOW_LOCKSCREEN_CONTENT, enabled)
                .apply();
    }
}
