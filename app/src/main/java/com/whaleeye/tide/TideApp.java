package com.whaleeye.tide;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

public final class TideApp extends Application {
    private static final long RELOCK_MS = 60_000L;
    static final boolean KEEP_STREAM_ON_LOCK = true;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable relockTask = () -> {
        if (!LockManager.isLockEnabled(this)) return;
        BioGate.cancel();
        applyUiLock();
        TideService.refreshNotification();
    };
    private int startedActivities;

    @Override
    public void onCreate() {
        super.onCreate();
        if (!LockManager.isLockEnabled(this)) {
            AutoUnlock.tryUnlock(this);
        }
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}

            @Override
            public void onActivityStarted(Activity activity) {
                startedActivities++;
                main.removeCallbacks(relockTask);
                enforceUiLock(activity);
            }

            @Override
            public void onActivityResumed(Activity activity) {
                SecureWindow.refresh(activity);
                enforceUiLock(activity);
            }

            @Override
            public void onActivityPaused(Activity activity) {}

            @Override
            public void onActivityStopped(Activity activity) {
                startedActivities = Math.max(0, startedActivities - 1);
                if (startedActivities == 0) {
                    main.removeCallbacks(relockTask);
                    main.postDelayed(relockTask, RELOCK_MS);
                }
            }
        });
        BroadcastReceiver screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    if (!LockManager.isLockEnabled(TideApp.this)) return;
                    BioGate.cancel();
                    applyUiLock();
                    main.removeCallbacks(relockTask);
                    TideService.refreshNotification();
                }
            }
        };
        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // ACTION_SCREEN_* 是受保护的系统广播；显示动态接收器必须允许系统投递。
            registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(screenReceiver, screenFilter);
        }
    }

    public static boolean ensureUnlocked(Activity activity) {
        if (LockManager.isUnlocked()) return true;
        redirectToLock(activity);
        return false;
    }

    private static void applyUiLock() {
        if (KEEP_STREAM_ON_LOCK) {
            LockManager.lockUi();
        } else {
            LockManager.lockAndWipe();
        }
    }

    private static void enforceUiLock(Activity activity) {
        if (LockManager.isUnlocked()
                || activity instanceof LockActivity
                || activity instanceof LaunchActivity) {
            return;
        }
        redirectToLock(activity);
    }

    private static void redirectToLock(Activity activity) {
        Intent lock = new Intent(activity, LockActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Intent source = activity.getIntent();
        if (source != null) {
            String conversationId = source.getStringExtra(ChatActivity.EXTRA_CONVERSATION_ID);
            String title = source.getStringExtra(ChatActivity.EXTRA_TITLE);
            android.net.Uri joinUri = source.getData();
            if (conversationId != null) {
                lock.putExtra(LockActivity.EXTRA_NEXT_CONVERSATION_ID, conversationId);
            }
            if (title != null) lock.putExtra(LockActivity.EXTRA_NEXT_TITLE, title);
            if (joinUri != null) {
                lock.putExtra(LockActivity.EXTRA_NEXT_JOIN_URI, joinUri.toString());
            }
        }
        activity.startActivity(lock);
        activity.finish();
    }
}
