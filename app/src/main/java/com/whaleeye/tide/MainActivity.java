package com.whaleeye.tide;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * 登录 / 邀请码注册。
 *
 * 登录页保留服务器地址（管理员入口）；注册模式隐藏它，服务器只从邀请链接来。
 */
public final class MainActivity extends Activity {
    public static final String EXTRA_JOIN_SERVER = "join_server";
    public static final String EXTRA_JOIN_CODE = "join_code";
    public static final String EXTRA_JOIN_KIND = "join_kind";

    private static final int REQ_DEVICE_RESET = 0x7104;

    private enum Mode {
        LOGIN,
        REGISTER
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;
    private Mode mode = Mode.LOGIN;
    private EditText inputServer;
    private EditText inputUsername;
    private EditText inputPassword;
    private EditText inputInvite;
    private LinearLayout serverRow;
    private LinearLayout inviteRow;
    private TextView btnLogin;
    private TextView textSwitchMode;
    private TextView textStatus;
    private TextView joinNotice;
    private String pendingJoinCode;
    private String pendingJoinKind;
    private String pendingResetUser;
    private String pendingResetPassword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        session = new Session(this);
        if (session.hasLogin()) {
            goHome();
            return;
        }

        setContentView(R.layout.activity_main);
        inputServer = findViewById(R.id.input_server);
        inputUsername = findViewById(R.id.input_username);
        inputPassword = findViewById(R.id.input_password);
        inputInvite = findViewById(R.id.input_invite);
        serverRow = findViewById(R.id.server_row);
        inviteRow = findViewById(R.id.invite_row);
        btnLogin = findViewById(R.id.btn_login);
        textSwitchMode = findViewById(R.id.text_switch_mode);
        textStatus = findViewById(R.id.text_status);
        joinNotice = findViewById(R.id.join_notice);

        pendingJoinCode = getIntent().getStringExtra(EXTRA_JOIN_CODE);
        pendingJoinKind = getIntent().getStringExtra(EXTRA_JOIN_KIND);
        String joinServer = getIntent().getStringExtra(EXTRA_JOIN_SERVER);
        inputServer.setText(session.baseUrl());
        if (!TextUtils.isEmpty(joinServer)) {
            session.setBaseUrl(joinServer);
            inputServer.setText(joinServer);
        }
        if (!TextUtils.isEmpty(pendingJoinCode)) {
            inputInvite.setText(pendingJoinCode);
            joinNotice.setVisibility(View.VISIBLE);
            mode = Mode.REGISTER;
        }
        if (!TextUtils.isEmpty(session.username())) {
            inputUsername.setText(session.username());
        }

        btnLogin.setOnClickListener(v -> submitCurrentMode());
        textSwitchMode.setOnClickListener(v -> {
            if (mode == Mode.LOGIN) {
                String server = inputServer.getText().toString().trim();
                if (!TextUtils.isEmpty(server)) session.setBaseUrl(server);
            }
            mode = mode == Mode.LOGIN ? Mode.REGISTER : Mode.LOGIN;
            renderMode();
        });
        inputPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitCurrentMode();
                return true;
            }
            return false;
        });
        inputInvite.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                doRegister();
                return true;
            }
            return false;
        });

        renderMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session != null && session.hasLogin() && !isFinishing()) {
            goHome();
        }
    }

    private void renderMode() {
        boolean register = mode == Mode.REGISTER;
        serverRow.setVisibility(register ? View.GONE : View.VISIBLE);
        inviteRow.setVisibility(register ? View.VISIBLE : View.GONE);
        if (!register && TextUtils.isEmpty(inputServer.getText())) {
            inputServer.setText(session.baseUrl());
        }
        btnLogin.setText(mode == Mode.REGISTER
                ? R.string.login_register_action
                : R.string.login_action);
        textSwitchMode.setText(mode == Mode.REGISTER
                ? R.string.login_switch_login
                : R.string.login_switch_register);
        textStatus.setText("");
    }

    private void submitCurrentMode() {
        if (mode == Mode.REGISTER) doRegister();
        else doLogin();
    }

    private void doLogin() {
        String user = inputUsername.getText().toString().trim();
        String pass = inputPassword.getText().toString();
        if (TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
            textStatus.setText(R.string.error_empty);
            return;
        }
        String base = ServerAddress.normalize(baseForRequest());
        if (base.isEmpty()) {
            textStatus.setText(R.string.error_server_empty);
            return;
        }
        if (base.startsWith("http://") && !BuildConfig.DEBUG) {
            textStatus.setText(R.string.error_https_required);
            return;
        }
        setBusy(true, R.string.login_action_loading);
        final String target = base;
        new Thread(() -> {
            try {
                JSONObject health = TideClient.probeHealth(target);
                if (!"ok".equals(health.optString("db", ""))) {
                    main.post(() -> Toast.makeText(this, R.string.error_server_db,
                            Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                main.post(() -> {
                    setBusy(false, R.string.login_action);
                    textStatus.setText(R.string.error_server_unreachable);
                });
                return;
            }
            session.setBaseUrl(target);
            performLogin(user, pass, null);
        }, "tide-login-probe").start();
    }

    private void doRegister() {
        String user = inputUsername.getText().toString().trim();
        String pass = inputPassword.getText().toString();
        String rawCode = inputInvite.getText().toString();
        if (TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
            textStatus.setText(R.string.error_empty);
            return;
        }
        if (TextUtils.isEmpty(rawCode.trim())) {
            textStatus.setText(R.string.error_invite_empty);
            return;
        }
        JoinLink link = JoinLink.fromAnyText(rawCode);
        if (link == null && JoinLink.looksLikeInviteLink(rawCode)) {
            textStatus.setText(R.string.error_invite_link_incomplete);
            return;
        }
        String code = link == null ? rawCode.trim() : link.code;
        if (link != null) {
            inputInvite.setText(code);
            if (!TextUtils.isEmpty(link.server) && !link.server.equals(session.baseUrl())) {
                session.setBaseUrl(link.server);
                Toast.makeText(this,
                        getString(R.string.server_switched, hostOf(link.server)),
                        Toast.LENGTH_SHORT).show();
            }
        }
        String base = baseForRequest();
        if (TextUtils.isEmpty(ServerAddress.normalize(base))) {
            textStatus.setText(R.string.onboard_paste_full_link);
            return;
        }
        if (base.startsWith("http://") && !BuildConfig.DEBUG) {
            textStatus.setText(R.string.error_https_required);
            return;
        }
        if (!TextUtils.isEmpty(base)) session.setBaseUrl(base);
        setBusy(true, R.string.login_register_loading);
        new Thread(() -> {
            try {
                TideClient client = new TideClient(session);
                JSONObject proof = deviceProof(client, user, "register");
                JSONObject response = client.register(
                        code, user, pass, DeviceIdentity.deviceName(), proof);
                main.post(() -> completeLogin(response, user));
            } catch (Exception e) {
                main.post(() -> fail("注册失败：" + friendly(e)));
            }
        }, "tide-register").start();
    }

    private String baseForRequest() {
        if (mode == Mode.REGISTER) return session.baseUrl();
        String value = inputServer.getText().toString().trim();
        return TextUtils.isEmpty(value) ? session.baseUrl() : value;
    }

    private static String hostOf(String server) {
        try {
            String host = java.net.URI.create(server).getHost();
            return host == null ? server : host;
        } catch (Exception e) {
            return server;
        }
    }

    private void performLogin(String user, String pass, String resetCode) {
        try {
            TideClient client = new TideClient(session);
            JSONObject proof = deviceProof(client, user, "login");
            JSONObject response = client.login(
                    user, pass, DeviceIdentity.deviceName(), proof, resetCode);
            main.post(() -> {
                if (response.optBoolean("approvalRequired", false)) {
                    openApprovalWait(response, user);
                    return;
                }
                completeLogin(response, user);
            });
        } catch (TideClient.TideException e) {
            if ("device-reset-required".equals(e.code)) {
                main.post(() -> openDeviceReset(user, pass));
                return;
            }
            main.post(() -> fail("登录失败：" + friendly(e)));
        } catch (Exception e) {
            main.post(() -> fail("登录失败：" + friendly(e)));
        }
    }

    private JSONObject deviceProof(TideClient client, String username, String purpose)
            throws Exception {
        String fingerprint = DeviceIdentity.fingerprint();
        JSONObject challenge = client.deviceChallenge(username, fingerprint, purpose);
        return DeviceIdentity.proof(challenge);
    }

    private void completeLogin(JSONObject response, String username) {
        try {
            AuthSession.apply(session, response, username);
            goHome();
        } catch (Exception e) {
            fail("登录失败：" + shortMessage(e));
        }
    }

    private void openApprovalWait(JSONObject pending, String username) {
        String approvalId = pending.optString("approvalId", "");
        String approvalToken = pending.optString("approvalToken", "");
        if (approvalId.isEmpty() || approvalToken.isEmpty()) {
            fail("登录失败：审批响应不完整");
            return;
        }
        setBusy(false, R.string.login_action);
        Intent intent = new Intent(this, DeviceApprovalWaitActivity.class)
                .putExtra(DeviceApprovalWaitActivity.EXTRA_APPROVAL_ID, approvalId)
                .putExtra(DeviceApprovalWaitActivity.EXTRA_APPROVAL_TOKEN, approvalToken)
                .putExtra(DeviceApprovalWaitActivity.EXTRA_VERIFICATION_CODE,
                        pending.optString("verificationCode", ""))
                .putExtra(DeviceApprovalWaitActivity.EXTRA_EXPIRES_AT,
                        pending.optString("expiresAt", ""))
                .putExtra(DeviceApprovalWaitActivity.EXTRA_USERNAME, username)
                .putExtra(DeviceApprovalWaitActivity.EXTRA_JOIN_CODE, pendingJoinCode)
                .putExtra(DeviceApprovalWaitActivity.EXTRA_JOIN_KIND, pendingJoinKind);
        startActivity(intent);
    }

    private void openDeviceReset(String user, String pass) {
        pendingResetUser = user;
        pendingResetPassword = pass;
        setBusy(false, R.string.login_action);
        Intent intent = new Intent(this, DeviceResetActivity.class)
                .putExtra(DeviceResetActivity.EXTRA_USERNAME, user);
        startActivityForResult(intent, REQ_DEVICE_RESET);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_DEVICE_RESET) return;
        if (resultCode == RESULT_OK && data != null) {
            String code = data.getStringExtra(DeviceResetActivity.EXTRA_RESET_CODE);
            if (!TextUtils.isEmpty(code) && pendingResetUser != null && pendingResetPassword != null) {
                setBusy(true, R.string.login_action_loading);
                new Thread(() -> performLogin(
                        pendingResetUser, pendingResetPassword, code),
                        "tide-device-reset").start();
                return;
            }
        }
        setBusy(false, R.string.login_action);
    }

    private void setBusy(boolean busy, int loadingText) {
        btnLogin.setEnabled(!busy);
        inputUsername.setEnabled(!busy);
        inputPassword.setEnabled(!busy);
        inputInvite.setEnabled(!busy);
        inputServer.setEnabled(!busy);
        textSwitchMode.setEnabled(!busy);
        btnLogin.setAlpha(busy ? 0.6f : 1f);
        textSwitchMode.setAlpha(busy ? 0.5f : 1f);
        if (busy) {
            textStatus.setText("");
            btnLogin.setText(loadingText);
        } else {
            btnLogin.setText(mode == Mode.REGISTER
                    ? R.string.login_register_action
                    : R.string.login_action);
        }
    }

    private void fail(String message) {
        setBusy(false, R.string.login_action);
        textStatus.setText(message);
    }

    private void goHome() {
        Intent home = new Intent(this, MessagesActivity.class);
        if (!TextUtils.isEmpty(pendingJoinCode)) {
            home.putExtra(EXTRA_JOIN_CODE, pendingJoinCode);
            home.putExtra(EXTRA_JOIN_KIND, pendingJoinKind);
        }
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(home);
        finish();
    }

    static String friendly(Exception e) {
        if (e instanceof java.net.UnknownHostException) {
            return "找不到服务器地址，请检查网络";
        }
        if (e instanceof java.net.ConnectException
                || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.NoRouteToHostException) {
            return "连不上服务器：网络被拦或安装包过旧，请换网络重试或更新到最新版";
        }
        if (e instanceof java.net.SocketException) {
            return "网络连接被中断，请换网络后重试";
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return "与服务器的加密连接失败，请更新到最新版";
        }
        if (e instanceof TideClient.TideException) {
            TideClient.TideException te = (TideClient.TideException) e;
            if ("device-key-required".equals(te.code)) return "需要设备密钥，请更新 App";
            if ("device-reset-required".equals(te.code)) return "需要管理员重置设备信任";
            if ("invalid-credentials".equals(te.code)
                    || "bad-credentials".equals(te.code)) {
                return "账号或密码不对";
            }
            if ("user-disabled".equals(te.code)) return "账号已停用";
            if ("invite-expired".equals(te.code)) return "邀请码已过期";
            if ("invite-used".equals(te.code) || "invite-exhausted".equals(te.code)) {
                return "邀请码已被用完";
            }
            if ("invite-revoked".equals(te.code)) return "邀请码已撤销";
            if ("username-taken".equals(te.code)) return "账号已被占用";
            if ("invite-invalid".equals(te.code)) return "邀请码不存在，请核对后重试";
            if ("group-invite-login-required".equals(te.code)) return "这是群邀请码，请登录后用「加入群聊」";
            if ("bad-request".equals(te.code)) return "填写有误：" + te.getMessage();
            if (te.status == 401) return "账号或密码不对";
        }
        return shortMessage(e);
    }

    private static String shortMessage(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.toString() : message;
    }

    @Override
    protected void onDestroy() {
        pendingResetPassword = null;
        super.onDestroy();
    }
}
