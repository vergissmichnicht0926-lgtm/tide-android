package com.whaleeye.tide;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Bot 设置：AI 姓名、个签、头像和人格覆盖。 */
public final class AdminSettingsActivity extends Activity {
    private static final int REQ_PICK_AVATAR = 0x7401;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Session session;
    private TideClient client;
    private EditText inputName;
    private EditText inputSignature;
    private EditText inputPersona;
    private ImageView avatarImage;
    private TextView avatarInitial;
    private TextView error;
    private ProgressBar progress;
    private String avatarData = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.apply(this);
        session = new Session(this);
        if (!session.hasLogin() || !session.isAdmin()) {
            startActivity(new Intent(this, MessagesActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }
        client = new TideClient(session);
        setContentView(R.layout.activity_admin_settings);
        inputName = findViewById(R.id.input_ai_name);
        inputSignature = findViewById(R.id.input_ai_signature);
        inputPersona = findViewById(R.id.input_persona);
        avatarImage = findViewById(R.id.ai_avatar_image);
        avatarInitial = findViewById(R.id.ai_avatar_initial);
        error = findViewById(R.id.text_settings_error);
        progress = findViewById(R.id.progress_settings);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_save_persona).setOnClickListener(v -> save());
        FrameLayout frame = findViewById(R.id.ai_avatar_frame);
        frame.setOnClickListener(v -> pickAvatar());
        load();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        error.setText("");
        new Thread(() -> {
            try {
                JSONObject response = client.adminSettings();
                JSONObject settings = response.optJSONObject("settings");
                String name = settings == null ? "" : settings.optString("aiName", "");
                String signature = settings == null ? "" : settings.optString("aiSignature", "");
                String avatar = settings == null ? "" : settings.optString("aiAvatar", "");
                String persona = settings == null ? "" : settings.optString("personaOverride", "");
                main.post(() -> {
                    progress.setVisibility(View.GONE);
                    inputName.setText(TextUtils.isEmpty(name) || "null".equals(name)
                            ? "汐语" : name);
                    inputSignature.setText("null".equals(signature) ? "" : signature);
                    inputPersona.setText("null".equals(persona) ? "" : persona);
                    avatarData = "null".equals(avatar) ? "" : avatar;
                    renderAvatar();
                });
            } catch (Exception e) {
                main.post(() -> {
                    progress.setVisibility(View.GONE);
                    error.setText("加载失败：" + message(e));
                });
            }
        }, "tide-admin-settings").start();
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
        try {
            avatarData = encodeAvatar(data.getData());
            renderAvatar();
        } catch (Exception e) {
            Toast.makeText(this, R.string.profile_avatar_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private String encodeAvatar(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 512) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(in, null, options);
        }
        if (bitmap == null) throw new IllegalStateException("decode-failed");
        int side = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (side > 256) {
            float scale = 256f / side;
            Bitmap scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    Math.max(1, Math.round(bitmap.getWidth() * scale)),
                    Math.max(1, Math.round(bitmap.getHeight() * scale)),
                    true);
            if (scaled != bitmap) bitmap.recycle();
            bitmap = scaled;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out);
        bitmap.recycle();
        return "data:image/jpeg;base64,"
                + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private void renderAvatar() {
        String name = inputName.getText().toString().trim();
        avatarInitial.setText(TextUtils.isEmpty(name) ? "汐" : name.substring(0, 1));
        Bitmap bitmap = decodeAvatar(avatarData);
        if (bitmap == null) {
            avatarImage.setImageDrawable(null);
            avatarInitial.setVisibility(View.VISIBLE);
        } else {
            avatarImage.setImageBitmap(bitmap);
            avatarInitial.setVisibility(View.GONE);
        }
    }

    private static Bitmap decodeAvatar(String value) {
        if (TextUtils.isEmpty(value) || !value.startsWith("data:image/")) return null;
        int comma = value.indexOf(',');
        if (comma < 0) return null;
        try {
            byte[] bytes = Base64.decode(value.substring(comma + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    private void save() {
        progress.setVisibility(View.VISIBLE);
        error.setText("");
        String name = inputName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) name = "汐语";
        String signature = inputSignature.getText().toString().trim();
        String persona = inputPersona.getText().toString();
        final String finalName = name;
        final String finalSignature = signature;
        final String finalAvatar = avatarData;
        new Thread(() -> {
            try {
                JSONObject patch = new JSONObject();
                patch.put("aiName", finalName);
                patch.put("aiSignature", finalSignature);
                patch.put("aiAvatar", TextUtils.isEmpty(finalAvatar)
                        ? JSONObject.NULL : finalAvatar);
                patch.put("personaOverride",
                        TextUtils.isEmpty(persona) ? JSONObject.NULL : persona);
                client.updateAdminSettings(patch);
                AiProfileStore.save(this, finalName, finalSignature, finalAvatar);
                main.post(() -> {
                    progress.setVisibility(View.GONE);
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (Exception e) {
                main.post(() -> {
                    progress.setVisibility(View.GONE);
                    error.setText("保存失败：" + message(e));
                });
            }
        }, "tide-save-settings").start();
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
