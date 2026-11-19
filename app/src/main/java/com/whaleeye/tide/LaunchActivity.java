package com.whaleeye.tide;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** 冷启动路由页：品牌由系统 Splash 承担，这里只保留一帧过渡。 */
public final class LaunchActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch);
        main.post(this::route);
    }

    private void route() {
        if (isFinishing()) return;
        if (!LockManager.isUnlocked()) {
            startActivity(new Intent(this, LockActivity.class));
            finish();
            return;
        }
        Session session = new Session(this);
        Intent next;
        if (session.hasLogin()) {
            next = new Intent(this, MessagesActivity.class);
        } else if (OnboardingActivity.isPending(this)) {
            next = new Intent(this, OnboardingActivity.class);
        } else {
            next = new Intent(this, MainActivity.class);
        }
        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(next);
        finish();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
