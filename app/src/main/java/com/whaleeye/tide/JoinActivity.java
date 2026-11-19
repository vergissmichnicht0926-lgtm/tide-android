package com.whaleeye.tide;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/** 接收 tide://join 深链，先展示目标服务器，再由用户确认切换/登录/兑换。 */
public final class JoinActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;
    private JoinLink link;
    private boolean sameServer;
    private boolean loggedIn;
    private TextView textJoinTitle;
    private TextView textJoinSubtitle;
    private TextView textServer;
    private TextView textCode;
    private TextView textKind;
    private TextView textCrossServer;
    private TextView textJoinError;
    private TextView btnContinue;
    private TextView btnCancel;
    private ImageButton btnClose;
    private LinearLayout joinCard;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.apply(this);
        setContentView(R.layout.activity_join);

        textJoinTitle = findViewById(R.id.text_join_title);
        textJoinSubtitle = findViewById(R.id.text_join_subtitle);
        textServer = findViewById(R.id.text_server);
        textCode = findViewById(R.id.text_code);
        textKind = findViewById(R.id.text_kind);
        textCrossServer = findViewById(R.id.text_cross_server);
        textJoinError = findViewById(R.id.text_join_error);
        btnContinue = findViewById(R.id.btn_continue);
        btnCancel = findViewById(R.id.btn_cancel);
        btnClose = findViewById(R.id.btn_close);
        joinCard = findViewById(R.id.join_card);
        progress = findViewById(R.id.progress_join);

        btnClose.setOnClickListener(v -> finish());
        btnCancel.setOnClickListener(v -> finish());

        try {
            link = JoinLink.parse(getIntent().getDataString());
        } catch (IllegalArgumentException e) {
            renderError(e.getMessage());
            return;
        }

        session = new Session(this);
        sameServer = sameServer(session.baseUrl(), link.server);
        loggedIn = session.hasLogin();
        textServer.setText(serverLabel(link.server));
        textCode.setText(R.string.join_link_recognized);
        textKind.setText("group".equals(link.kind)
                ? R.string.join_kind_group
                : R.string.join_kind_bot);
        if (!sameServer) {
            textCrossServer.setVisibility(View.VISIBLE);
            textCrossServer.setText(loggedIn
                    ? R.string.join_cross_server
                    : R.string.join_cross_server_logged_out);
        }
        if (loggedIn && sameServer && "group".equals(link.kind)) {
            btnContinue.setText(R.string.join_join_group);
        }
        btnContinue.setOnClickListener(v -> continueJoin());
    }

    private void renderError(String message) {
        textJoinTitle.setText(R.string.join_invalid_title);
        textJoinSubtitle.setText(TextUtils.isEmpty(message) ? "" : message);
        joinCard.setVisibility(View.GONE);
        textCrossServer.setVisibility(View.GONE);
        textJoinError.setText("");
        btnContinue.setVisibility(View.GONE);
        btnCancel.setText(R.string.join_cancel);
        progress.setVisibility(View.GONE);
    }

    private void continueJoin() {
        if (!sameServer && loggedIn) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.join_title)
                    .setMessage(getString(R.string.join_cross_server))
                    .setPositiveButton(R.string.join_continue, (d, w) -> applyJoin())
                    .setNegativeButton(R.string.join_cancel, null)
                    .show();
            return;
        }
        applyJoin();
    }

    private void applyJoin() {
        setBusy(true);
        textJoinError.setText("");
        if (!sameServer) {
            TideService.stop(this);
            LockManager.lockAndWipe();
            session.clearLogin();
            session.setBaseUrl(link.server);
        }
        if (!session.hasLogin()) {
            Class<?> target = OnboardingActivity.isPending(this)
                    ? OnboardingActivity.class
                    : MainActivity.class;
            Intent login = new Intent(this, target)
                    .putExtra(MainActivity.EXTRA_JOIN_SERVER, link.server)
                    .putExtra(MainActivity.EXTRA_JOIN_CODE, link.code)
                    .putExtra(MainActivity.EXTRA_JOIN_KIND, link.kind);
            startActivity(login);
            finish();
            return;
        }
        if (!"group".equals(link.kind)) {
            Toast.makeText(this,
                    "私聊邀请已属于当前账号，无需重复兑换",
                    Toast.LENGTH_LONG).show();
            goHome();
            return;
        }
        new Thread(() -> {
            try {
                JSONObject response = new TideClient(session).acceptGroupInvite(link.code);
                String conversationId = response.optString("conversationId", "");
                main.post(() -> {
                    Toast.makeText(this, R.string.join_success, Toast.LENGTH_SHORT).show();
                    if (!conversationId.isEmpty()) {
                        Intent chat = new Intent(this, ChatActivity.class)
                                .putExtra(ChatActivity.EXTRA_CONVERSATION_ID, conversationId)
                                .putExtra(ChatActivity.EXTRA_TITLE, "群聊");
                        chat.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(chat);
                    } else {
                        goHome();
                        return;
                    }
                    finish();
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? e.toString() : e.getMessage();
                if (e instanceof TideClient.TideException
                        && "already-member".equals(((TideClient.TideException) e).code)) {
                    main.post(() -> {
                        Toast.makeText(this,
                                R.string.join_already_member, Toast.LENGTH_LONG).show();
                        goHome();
                    });
                    return;
                }
                main.post(() -> {
                    setBusy(false);
                    textJoinError.setText("加入失败：" + message);
                });
            }
        }, "tide-join").start();
    }

    private void goHome() {
        Intent home = new Intent(this, MessagesActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(home);
        finish();
    }

    private void setBusy(boolean busy) {
        btnContinue.setEnabled(!busy);
        btnCancel.setEnabled(!busy);
        btnClose.setEnabled(!busy);
        btnContinue.setAlpha(busy ? 0.6f : 1f);
        btnCancel.setAlpha(busy ? 0.6f : 1f);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (busy) btnContinue.setText(R.string.join_busy);
        else if (loggedIn && sameServer && "group".equals(link.kind)) {
            btnContinue.setText(R.string.join_join_group);
        } else {
            btnContinue.setText(R.string.join_continue);
        }
    }

    private static String serverLabel(String server) {
        Uri uri = Uri.parse(server);
        String host = uri.getHost();
        if (host == null || host.isEmpty()) return server;
        String path = uri.getPath();
        return path == null || path.isEmpty() || "/".equals(path)
                ? host
                : host + path;
    }

    private static boolean sameServer(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static String normalize(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }
}
