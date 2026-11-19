package com.whaleeye.tide;

import android.app.Activity;
import android.content.Context;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** 默认阻止截屏；QA 包或账号与安全里的开关可以放开普通页面。 */
final class SecureWindow {
    private static final String PREFS = "tide.security";
    private static final String K_ALLOW = "allow_screenshots";
    private static final Set<Activity> SECURE =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());
    private static final Set<Activity> ALWAYS_SECURE =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());

    private SecureWindow() {
    }

    static void apply(Activity activity) {
        SECURE.add(activity);
        refresh(activity);
    }

    /** 锁屏、设备审批确认码、设备重置码：即使打开“允许截屏”也保持保护。 */
    static void applySensitive(Activity activity) {
        SECURE.add(activity);
        ALWAYS_SECURE.add(activity);
        refresh(activity);
    }

    static void refresh(Activity activity) {
        if (!SECURE.contains(activity)) return;
        boolean allowed = BuildConfig.ALLOW_SCREENSHOTS
                || (!ALWAYS_SECURE.contains(activity) && isAllowed(activity));
        if (allowed) {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    static boolean isAllowed(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(K_ALLOW, false);
    }

    static void setAllowed(Context context, boolean allowed) {
        context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(K_ALLOW, allowed)
                .apply();
        for (Activity activity : new ArrayList<>(SECURE)) {
            refresh(activity);
        }
    }
}
