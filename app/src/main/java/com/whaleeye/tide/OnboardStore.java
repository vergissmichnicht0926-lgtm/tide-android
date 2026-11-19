package com.whaleeye.tide;

import android.content.Context;
import android.content.SharedPreferences;

/** 首次引导只出现一次；完成注册或选择直接登录后落盘。 */
final class OnboardStore {
    private static final String PREFS = "tide.onboard";
    private static final String K_DONE = "done";

    private OnboardStore() {
    }

    static boolean isDone(Context context) {
        return prefs(context).getBoolean(K_DONE, false);
    }

    static void markDone(Context context) {
        prefs(context).edit().putBoolean(K_DONE, true).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
