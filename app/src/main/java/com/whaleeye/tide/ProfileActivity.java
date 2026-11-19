package com.whaleeye.tide;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** 个人信息：本机昵称、签名、头像；账号只读。 */
public final class ProfileActivity extends Activity {
    private static final int REQ_PICK_AVATAR = 0x7301;

    private Session session;
    private ImageView avatarImage;
    private TextView avatarInitial;
    private EditText inputName;
    private EditText inputSignature;
    private String avatarUri;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TideClient client;
    private boolean saving;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.apply(this);
        session = new Session(this);
        if (!session.hasLogin()) {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }
        setContentView(R.layout.activity_profile);
        client = new TideClient(session);
        avatarImage = findViewById(R.id.profile_avatar_image);
        avatarInitial = findViewById(R.id.profile_avatar_initial);
        inputName = findViewById(R.id.input_profile_name);
        inputSignature = findViewById(R.id.input_profile_signature);
        TextView textAccount = findViewById(R.id.text_profile_account);
        TextView role = findViewById(R.id.profile_role);

        String display = ProfileStore.displayName(this, session.displayName());
        if (TextUtils.isEmpty(display)) display = session.username();
        inputName.setText(display);
        inputSignature.setText(ProfileStore.signature(this));
        textAccount.setText(session.username() == null ? "" : session.username());
        role.setText(session.isAdmin() ? R.string.home_admin : R.string.home_guest);
        avatarUri = ProfileStore.avatarUri(this);
        renderAvatar(display);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_settings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btn_save).setOnClickListener(v -> save());
        FrameLayout avatarFrame = findViewById(R.id.profile_avatar_frame);
        avatarFrame.setOnClickListener(v -> pickAvatar());
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
        renderAvatar(inputName.getText().toString());
    }

    private void renderAvatar(String display) {
        String initial = TextUtils.isEmpty(display) ? "汐" : display.substring(0, 1);
        avatarInitial.setText(initial);
        if (TextUtils.isEmpty(avatarUri)) {
            avatarImage.setImageDrawable(null);
            avatarInitial.setVisibility(android.view.View.VISIBLE);
            return;
        }
        try {
            avatarImage.setImageURI(Uri.parse(avatarUri));
        } catch (Exception e) {
            avatarImage.setImageDrawable(null);
        }
        avatarInitial.setVisibility(avatarImage.getDrawable() == null
                ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void save() {
        if (saving) return;
        String name = inputName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            name = TextUtils.isEmpty(session.displayName())
                    ? session.username() : session.displayName();
        }
        if (name == null || name.isEmpty() || name.length() > 30) {
            Toast.makeText(this, "昵称必须是 1~30 字", Toast.LENGTH_SHORT).show();
            return;
        }
        ProfileStore.save(this, name,
                inputSignature.getText().toString().trim(), avatarUri);
        saving = true;
        findViewById(R.id.btn_save).setEnabled(false);
        final String finalName = name;
        new Thread(() -> {
            String dataUri = null;
            String error = null;
            try {
                if (!TextUtils.isEmpty(avatarUri)) {
                    dataUri = avatarDataUri(Uri.parse(avatarUri));
                }
                client.updateMe(finalName, dataUri);
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            final String failure = error;
            main.post(() -> {
                saving = false;
                if (isFinishing()) return;
                if (failure == null) {
                    Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this,
                            "已保存到本机，云端同步失败：" + failure, Toast.LENGTH_LONG).show();
                }
                finish();
            });
        }, "tide-profile-save").start();
    }

    /** 头像压到 256px、JPEG 85，再转 data URI；透明底先铺白，避免变黑。 */
    private String avatarDataUri(Uri uri) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("无法读取头像");
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("无法读取头像");
        }
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > 512) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap decoded;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("无法读取头像");
            decoded = BitmapFactory.decodeStream(new ByteArrayInputStream(
                    readAll(in)), null, options);
        }
        if (decoded == null) throw new IOException("头像解码失败");

        int side = Math.max(decoded.getWidth(), decoded.getHeight());
        float scale = side > 256 ? 256f / side : 1f;
        int width = Math.max(1, Math.round(decoded.getWidth() * scale));
        int height = Math.max(1, Math.round(decoded.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, width, height, true);
        Bitmap flattened = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(flattened);
        canvas.drawColor(0xFFFFFFFF);
        canvas.drawBitmap(scaled, 0, 0, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        flattened.compress(Bitmap.CompressFormat.JPEG, 85, out);
        if (scaled != decoded) decoded.recycle();
        scaled.recycle();
        flattened.recycle();
        return "data:image/jpeg;base64,"
                + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
