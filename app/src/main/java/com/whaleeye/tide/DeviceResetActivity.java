package com.whaleeye.tide;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

/** 管理员重置设备信任后，新设备在这里输入一次性 8 位重绑码。 */
public final class DeviceResetActivity extends Activity {
    public static final String EXTRA_USERNAME = "username";
    public static final String EXTRA_RESET_CODE = "reset_code";

    private EditText inputCode;
    private TextView textError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.applySensitive(this);
        setContentView(R.layout.activity_device_reset);

        String username = getIntent().getStringExtra(EXTRA_USERNAME);
        TextView account = findViewById(R.id.text_account);
        account.setText(TextUtils.isEmpty(username) ? "" : username);
        inputCode = findViewById(R.id.input_reset_code);
        textError = findViewById(R.id.text_reset_error);

        findViewById(R.id.btn_back).setOnClickListener(v -> cancel());
        findViewById(R.id.btn_reset_cancel).setOnClickListener(v -> cancel());
        findViewById(R.id.btn_reset_submit).setOnClickListener(v -> submit());
        inputCode.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit();
                return true;
            }
            return false;
        });
    }

    private void submit() {
        String code = inputCode.getText().toString().trim();
        if (!ResetCode.isValid(code)) {
            textError.setText(R.string.reset_error_format);
            return;
        }
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESET_CODE, code));
        finish();
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    public void onBackPressed() {
        cancel();
    }
}
