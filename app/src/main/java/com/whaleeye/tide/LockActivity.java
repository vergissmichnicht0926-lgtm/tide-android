package com.whaleeye.tide;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 应用锁：六点 + 自绘数字键盘。
 *
 * 只负责界面状态与输入；PIN 校验、失败计数、五次擦除和生物识别密钥
 * 仍由 {@link LockManager} / {@link BioGate} 持有。
 */
public final class LockActivity extends Activity {
    public static final String EXTRA_NEXT_CONVERSATION_ID = "next_conversation_id";
    public static final String EXTRA_NEXT_TITLE = "next_conversation_title";
    public static final String EXTRA_NEXT_JOIN_URI = "next_join_uri";

    private enum Mode {
        SETUP_FIRST,
        SETUP_CONFIRM,
        UNLOCK
    }

    private static final int PIN_LENGTH = 6;
    private static final int[] KEY_IDS = {
            R.id.key_0, R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4,
            R.id.key_5, R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9
    };

    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder entered = new StringBuilder(PIN_LENGTH);
    private final View[] dots = new View[PIN_LENGTH];

    private Mode mode = Mode.UNLOCK;
    private String firstPin;
    private boolean busy;
    private boolean bioPrompted;
    private boolean justConfigured;
    private String nextConversationId;
    private String nextTitle;
    private String nextJoinUri;

    private LinearLayout pinDots;
    private LinearLayout bioAction;
    private TextView title;
    private TextView subtitle;
    private TextView error;
    private TextView resetAction;
    private ImageButton backspace;
    private ProgressBar progress;
    private ObjectAnimator shakeAnimator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SecureWindow.applySensitive(this);
        nextConversationId = getIntent().getStringExtra(EXTRA_NEXT_CONVERSATION_ID);
        nextTitle = getIntent().getStringExtra(EXTRA_NEXT_TITLE);
        nextJoinUri = getIntent().getStringExtra(EXTRA_NEXT_JOIN_URI);

        if (LockManager.isUnlocked()) {
            route();
            return;
        }

        setContentView(R.layout.activity_lock);
        pinDots = findViewById(R.id.pin_dots);
        bioAction = findViewById(R.id.btn_bio);
        title = findViewById(R.id.text_lock_title);
        subtitle = findViewById(R.id.text_lock_subtitle);
        error = findViewById(R.id.text_lock_error);
        resetAction = findViewById(R.id.btn_reset);
        backspace = findViewById(R.id.key_backspace);
        progress = findViewById(R.id.progress_lock);

        for (int i = 0; i < PIN_LENGTH; i++) {
            dots[i] = pinDots.getChildAt(i);
        }
        for (int digit = 0; digit <= 9; digit++) {
            final int value = digit;
            findViewById(KEY_IDS[digit]).setOnClickListener(v -> onDigit(value));
        }
        backspace.setOnClickListener(v -> onBackspace());
        bioAction.setOnClickListener(v -> promptBiometric());
        resetAction.setOnClickListener(v -> confirmClearLocal());
        title.setOnLongClickListener(v -> {
            if (mode == Mode.UNLOCK) confirmClearLocal();
            return true;
        });

        mode = LockManager.isConfigured(this) ? Mode.UNLOCK : Mode.SETUP_FIRST;
        renderMode();

        if (mode == Mode.UNLOCK) {
            int lost = LockManager.migrationLostCount(this);
            if (lost > 0) {
                error.setText(getString(R.string.lock_migration_lost, lost));
            } else if (BioGate.needsRebind(this)) {
                error.setText(BioGate.reason(this));
            }
        }
    }

    private void renderMode() {
        entered.setLength(0);
        setBusy(false);
        error.setText("");
        if (mode == Mode.SETUP_FIRST) {
            title.setText(R.string.lock_setup_title);
            subtitle.setText(R.string.lock_setup_subtitle);
        } else if (mode == Mode.SETUP_CONFIRM) {
            title.setText(R.string.lock_confirm_title);
            subtitle.setText(R.string.lock_confirm_subtitle);
        } else {
            title.setText(R.string.lock_welcome);
            subtitle.setText(R.string.lock_unlock_subtitle);
        }
        bioAction.setVisibility(mode == Mode.UNLOCK && BioGate.isEnabled(this)
                ? View.VISIBLE : View.GONE);
        resetAction.setVisibility(View.GONE);
        updateDots();
    }

    private void onDigit(int digit) {
        if (busy || entered.length() >= PIN_LENGTH) return;
        entered.append((char) ('0' + digit));
        updateDots();
        if (entered.length() == PIN_LENGTH) {
            setBusy(true);
            main.postDelayed(this::submit, 70);
        }
    }

    private void onBackspace() {
        if (busy || entered.length() == 0) return;
        entered.deleteCharAt(entered.length() - 1);
        updateDots();
    }

    private void submit() {
        if (entered.length() != PIN_LENGTH) {
            setBusy(false);
            return;
        }
        final String pin = entered.toString();
        if (mode == Mode.SETUP_FIRST) {
            firstPin = pin;
            mode = Mode.SETUP_CONFIRM;
            renderMode();
            return;
        }
        if (mode == Mode.SETUP_CONFIRM) {
            if (!pin.equals(firstPin)) {
                String message = getString(R.string.error_pin_mismatch);
                mode = Mode.SETUP_FIRST;
                firstPin = null;
                renderMode();
                error.setText(message);
                shake();
                return;
            }
            setBusy(true);
            new Thread(() -> {
                try {
                    LockManager.configure(this, pin);
                    main.post(() -> {
                        setBusy(false);
                        justConfigured = true;
                        afterPinUnlock();
                    });
                } catch (RuntimeException e) {
                    main.post(() -> {
                        setBusy(false);
                        error.setText("设置失败：" + shortMessage(e));
                        shake();
                    });
                }
            }, "tide-pin-setup").start();
            return;
        }

        setBusy(true);
        new Thread(() -> {
            LockManager.UnlockResult result;
            try {
                result = LockManager.unlock(this, pin);
            } catch (RuntimeException e) {
                main.post(() -> {
                    setBusy(false);
                    error.setText("解锁失败：" + shortMessage(e));
                    shake();
                });
                return;
            }
            main.post(() -> handleUnlockResult(result));
        }, "tide-pin-unlock").start();
    }

    private void handleUnlockResult(LockManager.UnlockResult result) {
        if (result == LockManager.UnlockResult.OK) {
            setBusy(false);
            afterPinUnlock();
            return;
        }
        if (result == LockManager.UnlockResult.WIPED) {
            mode = Mode.SETUP_FIRST;
            firstPin = null;
            renderMode();
            error.setText(R.string.lock_wiped);
            return;
        }
        if (result == LockManager.UnlockResult.KEY_LOST) {
            setBusy(false);
            error.setText(R.string.lock_key_lost);
            bioAction.setVisibility(View.GONE);
            resetAction.setVisibility(View.VISIBLE);
            entered.setLength(0);
            updateDots();
            return;
        }
        setBusy(false);
        error.setText(R.string.error_pin_wrong);
        entered.setLength(0);
        updateDots();
        shake();
    }

    private void updateDots() {
        int filled = entered.length();
        for (int i = 0; i < PIN_LENGTH; i++) {
            dots[i].setBackgroundResource(i < filled
                    ? R.drawable.bg_pin_dot_on
                    : R.drawable.bg_pin_dot_off);
        }
    }

    private void shake() {
        pinDots.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (shakeAnimator != null) shakeAnimator.cancel();
        shakeAnimator = ObjectAnimator.ofFloat(
                pinDots, "translationX", 0f, -8f, 8f, -6f, 6f, 0f);
        shakeAnimator.setDuration(260);
        shakeAnimator.start();
    }

    private void setBusy(boolean value) {
        busy = value;
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        for (int id : KEY_IDS) {
            View key = findViewById(id);
            if (key != null) key.setEnabled(!value);
        }
        backspace.setEnabled(!value);
        bioAction.setEnabled(!value);
        resetAction.setEnabled(!value);
    }

    private void afterPinUnlock() {
        if (BioGate.needsRebind(this)) {
            offerRebind();
            return;
        }
        if (justConfigured && BioGate.isSupported(this) && !BioGate.isEnabled(this)) {
            offerEnableBiometric();
            return;
        }
        int lost = LockManager.migrationLostCount(this);
        if (lost > 0) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.lock_key_lost)
                    .setMessage(getString(R.string.lock_migration_lost, lost))
                    .setPositiveButton(android.R.string.ok, (d, w) -> route())
                    .setCancelable(false)
                    .show();
            return;
        }
        route();
    }

    private void offerEnableBiometric() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.bio_enable_title)
                .setMessage(R.string.bio_enable_message)
                .setPositiveButton(R.string.bio_enable_now, (d, w) -> bindBiometric(true))
                .setNegativeButton(R.string.bio_enable_later, (d, w) -> route())
                .setCancelable(false)
                .show();
    }

    private void offerRebind() {
        String reason = BioGate.reason(this);
        String message = reason == null || reason.isEmpty()
                ? getString(R.string.bio_enable_message)
                : reason + "\n\n" + getString(R.string.bio_enable_message);
        new AlertDialog.Builder(this)
                .setTitle(R.string.bio_rebind_title)
                .setMessage(message)
                .setPositiveButton(R.string.bio_rebind_now, (d, w) -> bindBiometric(true))
                .setNegativeButton(R.string.bio_rebind_later, (d, w) -> {
                    LockManager.disableBio(this);
                    route();
                })
                .setCancelable(false)
                .show();
    }

    private void bindBiometric(boolean routeWhenDone) {
        BioGate.bind(this, new BioGate.Callback() {
            @Override
            public void onSuccess() {
                LockManager.setBioEnabled(LockActivity.this, true);
                if (routeWhenDone) route();
            }

            @Override
            public void onCancelled() {
                if (routeWhenDone) route();
            }

            @Override
            public void onFailure(String message) {
                error.setText(message);
                if (routeWhenDone) route();
            }

            @Override
            public void onPermanentFailure(String message) {
                error.setText(message);
                bioAction.setVisibility(View.GONE);
                if (routeWhenDone) route();
            }
        });
    }

    private void promptBiometric() {
        if (mode != Mode.UNLOCK || bioPrompted || !BioGate.isEnabled(this)) return;
        bioPrompted = true;
        error.setText("");
        BioGate.authenticate(this, new BioGate.Callback() {
            @Override
            public void onSuccess() {
                bioPrompted = false;
                route();
            }

            @Override
            public void onCancelled() {
                bioPrompted = false;
                error.setText(R.string.bio_cancelled);
            }

            @Override
            public void onFailure(String message) {
                bioPrompted = false;
                error.setText(message);
            }

            @Override
            public void onPermanentFailure(String message) {
                bioPrompted = false;
                error.setText(message);
                bioAction.setVisibility(View.GONE);
            }
        });
    }

    private void confirmClearLocal() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.action_clear_local)
                .setMessage(R.string.clear_local_confirm)
                .setPositiveButton(R.string.action_clear_local, (d, w) -> {
                    LockManager.wipeLocal(this);
                    mode = Mode.SETUP_FIRST;
                    firstPin = null;
                    renderMode();
                    error.setText(R.string.clear_local_done);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void route() {
        Session session = new Session(this);
        session.migrateSecrets();
        boolean loggedIn = session.hasLogin();
        String activeConversation = nextConversationId != null
                ? nextConversationId
                : session.conversationId();
        if (loggedIn && activeConversation != null) {
            TideService.startConversation(this, activeConversation, nextTitle);
        }
        Intent target;
        if (nextJoinUri != null) {
            target = new Intent(this, JoinActivity.class).setData(Uri.parse(nextJoinUri));
        } else if (!loggedIn) {
            target = OnboardingActivity.isPending(this)
                    ? new Intent(this, OnboardingActivity.class)
                    : new Intent(this, MainActivity.class);
        } else if (nextConversationId != null) {
            target = new Intent(this, ChatActivity.class)
                    .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, nextConversationId)
                    .putExtra(ChatActivity.EXTRA_TITLE, nextTitle);
        } else {
            target = new Intent(this, MessagesActivity.class);
        }
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(target);
        finish();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            onDigit(keyCode - KeyEvent.KEYCODE_0);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DEL) {
            onBackspace();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        BioGate.cancel();
        main.removeCallbacksAndMessages(null);
        if (shakeAnimator != null) {
            shakeAnimator.cancel();
            shakeAnimator = null;
        }
        super.onDestroy();
    }

    private static String shortMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
