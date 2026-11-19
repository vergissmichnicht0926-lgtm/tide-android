package com.whaleeye.tide;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * 首次引导三屏：邀请码说明 → 邀请码/账号/密码 → 昵称与可选头像。
 * 第 2 屏没有跳过入口，只有返回第 1 屏。
 */
public final class OnboardingActivity extends Activity {
    private static final int REQ_PICK_AVATAR = 0x7401;

    private Session session;
    private int step = 1;
    private boolean busy;
    private View pageIntro;
    private View pageAccount;
    private View pageProfile;
    private View[] dots;
    private EditText inputInvite;
    private EditText inputAccount;
    private EditText inputPassword;
    private EditText inputName;
    private TextView textAccountError;
    private TextView textNameError;
    private TextView btnAccountNext;
    private ImageView avatarImage;
    private ImageView avatarPlus;
    private String avatarUri = "";
    private String pendingJoinCode;
    private String pendingJoinKind;
    private String pendingJoinServer;

    static boolean isPending(Context context) {
        return !new Session(context).hasLogin() && !OnboardStore.isDone(context);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.apply(this);
        session = new Session(this);

        if (savedInstanceState != null) {
            step = savedInstanceState.getInt("step", 1);
            avatarUri = savedInstanceState.getString("avatar_uri", "");
            pendingJoinCode = savedInstanceState.getString("join_code");
            pendingJoinKind = savedInstanceState.getString("join_kind");
            pendingJoinServer = savedInstanceState.getString("join_server");
        } else {
            pendingJoinCode = getIntent().getStringExtra(MainActivity.EXTRA_JOIN_CODE);
            pendingJoinKind = getIntent().getStringExtra(MainActivity.EXTRA_JOIN_KIND);
            pendingJoinServer = getIntent().getStringExtra(MainActivity.EXTRA_JOIN_SERVER);
        }
        if (step == 3 && !session.hasLogin()) {
            step = 2;
        }
        if (step != 3 && session.hasLogin()) {
            goHome();
            return;
        }

        setContentView(R.layout.activity_onboard);
        pageIntro = findViewById(R.id.page_intro);
        pageAccount = findViewById(R.id.page_account);
        pageProfile = findViewById(R.id.page_profile);
        dots = new View[]{
                findViewById(R.id.onboard_dot_0),
                findViewById(R.id.onboard_dot_1),
                findViewById(R.id.onboard_dot_2)
        };
        inputInvite = findViewById(R.id.input_onboard_invite);
        inputAccount = findViewById(R.id.input_onboard_account);
        inputPassword = findViewById(R.id.input_onboard_password);
        inputName = findViewById(R.id.input_onboard_name);
        textAccountError = findViewById(R.id.text_onboard_account_error);
        textNameError = findViewById(R.id.text_onboard_name_error);
        btnAccountNext = findViewById(R.id.btn_onboard_account_next);
        avatarImage = findViewById(R.id.onboard_avatar_image);
        avatarPlus = findViewById(R.id.onboard_avatar_plus);
        avatarImage.setClipToOutline(true);

        bindActions();

        if (!TextUtils.isEmpty(pendingJoinServer)) {
            session.setBaseUrl(pendingJoinServer);
        }
        if (!TextUtils.isEmpty(pendingJoinCode)) {
            inputInvite.setText(OnboardRules.normalizeInvite(pendingJoinCode));
            inputInvite.setSelection(inputInvite.getText().length());
        }
        if (step < 1 || step > 3) step = 1;
        if (step == 3) renderAvatar();
        showStep(step);
    }

    private void bindActions() {
        findViewById(R.id.btn_intro_next).setOnClickListener(v -> {
            showStep(2);
            inputInvite.requestFocus();
        });
        findViewById(R.id.btn_intro_login).setOnClickListener(v -> openLogin());
        findViewById(R.id.btn_onboard_back).setOnClickListener(v -> showStep(1));
        btnAccountNext.setOnClickListener(v -> submitAccount());
        findViewById(R.id.btn_onboard_skip).setOnClickListener(v -> {
            OnboardStore.markDone(this);
            goHome();
        });
        findViewById(R.id.btn_onboard_enter).setOnClickListener(v -> finishProfile());
        findViewById(R.id.onboard_avatar_frame).setOnClickListener(v -> pickAvatar());

        TextWatcher clearAccountError = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                hideError(textAccountError);
            }
        };
        inputInvite.addTextChangedListener(clearAccountError);
        inputAccount.addTextChangedListener(clearAccountError);
        inputPassword.addTextChangedListener(clearAccountError);
        inputName.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                hideError(textNameError);
            }
        });
        inputAccount.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitAccount();
                return true;
            }
            return false;
        });
        inputPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitAccount();
                return true;
            }
            return false;
        });
        inputName.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                finishProfile();
                return true;
            }
            return false;
        });
    }

    private void showStep(int next) {
        step = next;
        pageIntro.setVisibility(next == 1 ? View.VISIBLE : View.GONE);
        pageAccount.setVisibility(next == 2 ? View.VISIBLE : View.GONE);
        pageProfile.setVisibility(next == 3 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < dots.length; i++) {
            boolean active = i == next - 1;
            View dot = dots[i];
            android.widget.LinearLayout.LayoutParams params =
                    (android.widget.LinearLayout.LayoutParams) dot.getLayoutParams();
            params.width = dp(active ? 18 : 6);
            params.height = dp(6);
            dot.setLayoutParams(params);
            dot.setBackgroundResource(active ? R.drawable.bg_dot_on : R.drawable.bg_dot_off);
        }
    }

    private void openLogin() {
        OnboardStore.markDone(this);
        Intent login = new Intent(this, MainActivity.class);
        if (!TextUtils.isEmpty(pendingJoinCode)) {
            login.putExtra(MainActivity.EXTRA_JOIN_CODE, pendingJoinCode);
            login.putExtra(MainActivity.EXTRA_JOIN_KIND, pendingJoinKind);
        }
        if (!TextUtils.isEmpty(pendingJoinServer)) {
            login.putExtra(MainActivity.EXTRA_JOIN_SERVER, pendingJoinServer);
        }
        login.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(login);
        finish();
    }

    private void submitAccount() {
        if (busy) return;
        String rawInvite = inputInvite.getText().toString();
        JoinLink linked = JoinLink.fromAnyText(rawInvite);
        if (linked == null && JoinLink.looksLikeInviteLink(rawInvite)) {
            showError(textAccountError, getString(R.string.error_invite_link_incomplete));
            return;
        }
        String code;
        if (linked != null) {
            // 粘贴的是整条邀请链接：顺手把服务器地址也切过去。
            code = linked.code;
            inputInvite.setText(code);
            inputInvite.setSelection(code.length());
            if (!TextUtils.isEmpty(linked.server)) {
                boolean changed = !linked.server.equals(session.baseUrl());
                session.setBaseUrl(linked.server);
                pendingJoinServer = linked.server;
                if (changed) {
                    Toast.makeText(this,
                            getString(R.string.server_switched, hostOf(linked.server)),
                            Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            code = OnboardRules.normalizeInvite(rawInvite);
        }
        if (TextUtils.isEmpty(ServerAddress.normalize(session.baseUrl()))) {
            showError(textAccountError, getString(R.string.onboard_paste_full_link));
            return;
        }
        String account = inputAccount.getText().toString().trim();
        String password = inputPassword.getText().toString();
        int error = OnboardRules.errorFor(code, account, password);
        if (error != 0) {
            showError(textAccountError, getString(error));
            return;
        }
        String base = session.baseUrl();
        if (base.startsWith("http://") && !BuildConfig.DEBUG) {
            showError(textAccountError, getString(R.string.error_https_required));
            return;
        }
        setBusy(true);
        new Thread(() -> {
            try {
                TideClient client = new TideClient(session);
                String fingerprint = DeviceIdentity.fingerprint();
                JSONObject challenge = client.deviceChallenge(account, fingerprint, "register");
                JSONObject proof = DeviceIdentity.proof(challenge);
                JSONObject response = client.register(
                        code, account, password, DeviceIdentity.deviceName(), proof);
                runOnUiThread(() -> completeRegister(response, account));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setBusy(false);
                    showError(textAccountError,
                            getString(R.string.onboard_register_failed, MainActivity.friendly(e)));
                });
            }
        }, "tide-onboard-register").start();
    }

    private static String hostOf(String server) {
        try {
            String host = java.net.URI.create(server).getHost();
            return host == null ? server : host;
        } catch (Exception e) {
            return server;
        }
    }

    private void completeRegister(JSONObject response, String account) {
        if (isFinishing() || isDestroyed()) return;
        // 账号已经建好；即使还要走设备审批，也不该让用户冷启动后重新填一遍邀请码。
        OnboardStore.markDone(this);
        if (response.optBoolean("approvalRequired", false)) {
            setBusy(false);
            openApprovalWait(response, account);
            return;
        }
        try {
            AuthSession.apply(session, response, account);
        } catch (Exception e) {
            setBusy(false);
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            showError(textAccountError,
                    getString(R.string.onboard_register_failed, message));
            return;
        }
        setBusy(false);
        String display = session.displayName();
        if (TextUtils.isEmpty(display)) display = account;
        inputName.setText(display);
        inputName.setSelection(display.length());
        showStep(3);
    }

    private void openApprovalWait(JSONObject pending, String account) {
        String approvalId = pending.optString("approvalId", "");
        String approvalToken = pending.optString("approvalToken", "");
        if (approvalId.isEmpty() || approvalToken.isEmpty()) {
            showError(textAccountError,
                    getString(R.string.onboard_register_failed,
                            getString(R.string.onboard_approval_incomplete)));
            return;
        }
        Intent intent = new Intent(this, DeviceApprovalWaitActivity.class)
                .putExtra(DeviceApprovalWaitActivity.EXTRA_APPROVAL_ID, approvalId)
                .putExtra(DeviceApprovalWaitActivity.EXTRA_APPROVAL_TOKEN, approvalToken)
                .putExtra(DeviceApprovalWaitActivity.EXTRA_VERIFICATION_CODE,
                        pending.optString("verificationCode", ""))
                .putExtra(DeviceApprovalWaitActivity.EXTRA_EXPIRES_AT,
                        pending.optString("expiresAt", ""))
                .putExtra(DeviceApprovalWaitActivity.EXTRA_USERNAME, account)
                .putExtra(DeviceApprovalWaitActivity.EXTRA_JOIN_CODE, pendingJoinCode)
                .putExtra(DeviceApprovalWaitActivity.EXTRA_JOIN_KIND, pendingJoinKind);
        startActivity(intent);
        finish();
    }

    private void finishProfile() {
        String name = inputName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            showError(textNameError, getString(R.string.onboard_error_name));
            return;
        }
        ProfileStore.save(this, name, ProfileStore.signature(this), avatarUri);
        OnboardStore.markDone(this);
        goHome();
    }

    private void pickAvatar() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        try {
            startActivityForResult(intent, REQ_PICK_AVATAR);
        } catch (Exception e) {
            Toast.makeText(this, R.string.profile_avatar_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_AVATAR || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        avatarUri = uri.toString();
        renderAvatar();
    }

    private void renderAvatar() {
        if (TextUtils.isEmpty(avatarUri)) {
            avatarImage.setImageDrawable(null);
            avatarImage.setVisibility(View.GONE);
            avatarPlus.setVisibility(View.VISIBLE);
            return;
        }
        try {
            avatarImage.setImageURI(Uri.parse(avatarUri));
        } catch (Exception e) {
            avatarImage.setImageDrawable(null);
        }
        boolean loaded = avatarImage.getDrawable() != null;
        avatarImage.setVisibility(loaded ? View.VISIBLE : View.GONE);
        avatarPlus.setVisibility(loaded ? View.GONE : View.VISIBLE);
    }

    private void setBusy(boolean value) {
        busy = value;
        inputInvite.setEnabled(!value);
        inputAccount.setEnabled(!value);
        inputPassword.setEnabled(!value);
        btnAccountNext.setEnabled(!value);
        btnAccountNext.setAlpha(value ? 0.6f : 1f);
        btnAccountNext.setText(value
                ? R.string.onboard_registering
                : R.string.onboard_account_next);
    }

    private void showError(TextView target, String message) {
        target.setText(message);
        target.setVisibility(View.VISIBLE);
    }

    private void hideError(TextView target) {
        if (target.getVisibility() == View.VISIBLE) {
            target.setText("");
            target.setVisibility(View.INVISIBLE);
        }
    }

    private void goHome() {
        Intent home = new Intent(this, MessagesActivity.class);
        if (!TextUtils.isEmpty(pendingJoinCode)) {
            home.putExtra(MainActivity.EXTRA_JOIN_CODE, pendingJoinCode);
            home.putExtra(MainActivity.EXTRA_JOIN_KIND, pendingJoinKind);
        }
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(home);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (busy) return;
        if (step == 2) {
            showStep(1);
            return;
        }
        if (step == 3) {
            goHome();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("step", step);
        outState.putString("avatar_uri", avatarUri);
        outState.putString("join_code", pendingJoinCode);
        outState.putString("join_kind", pendingJoinKind);
        outState.putString("join_server", pendingJoinServer);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
