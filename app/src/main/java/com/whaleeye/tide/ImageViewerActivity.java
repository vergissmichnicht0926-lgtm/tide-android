package com.whaleeye.tide;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;

/** 全屏图片查看器：双击 1x/2x，下拉关闭，保存到用户选择的位置。 */
public final class ImageViewerActivity extends Activity {
    public static final String EXTRA_ATTACHMENT_ID = "attachment_id";
    public static final String EXTRA_NAME = "attachment_name";
    private static final int REQ_SAVE = 0x7201;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ImageView image;
    private ProgressBar progress;
    private TextView error;
    private TextView save;
    private byte[] original;
    private Bitmap bitmap;
    private GestureDetector gestures;
    private float downY;
    private boolean dragging;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.apply(this);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        setContentView(R.layout.activity_image_viewer);

        image = findViewById(R.id.viewer_image);
        progress = findViewById(R.id.viewer_progress);
        error = findViewById(R.id.viewer_error);
        save = findViewById(R.id.btn_save);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        save.setOnClickListener(v -> chooseSaveTarget());

        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                toggleZoom(e.getX(), e.getY());
                return true;
            }
        });
        image.setOnTouchListener(this::onTouch);

        String attachmentId = getIntent().getStringExtra(EXTRA_ATTACHMENT_ID);
        if (attachmentId == null || attachmentId.isEmpty()) {
            showError("图片不存在");
            return;
        }
        new Thread(() -> {
            try {
                byte[] bytes = new TideClient(new Session(this)).downloadAttachment(attachmentId);
                Bitmap decoded = decode(bytes, 2400);
                main.post(() -> {
                    if (decoded == null) {
                        showError("图片解码失败");
                        return;
                    }
                    original = bytes;
                    bitmap = decoded;
                    progress.setVisibility(View.GONE);
                    image.setImageBitmap(decoded);
                });
            } catch (Exception e) {
                main.post(() -> showError("图片加载失败"));
            }
        }, "tide-viewer").start();
    }

    private boolean onTouch(View view, MotionEvent event) {
        gestures.onTouchEvent(event);
        if (image.getScaleX() > 1.01f) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = event.getY();
                dragging = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) return true;
                float dy = Math.max(0, event.getY() - downY);
                image.setTranslationY(dy);
                image.setAlpha(Math.max(0.2f, 1f - dy / image.getHeight()));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) return true;
                dragging = false;
                float drag = image.getTranslationY();
                if (drag > dp(96)) {
                    finish();
                } else {
                    image.animate().translationY(0f).alpha(1f).setDuration(150).start();
                }
                return true;
            default:
                return true;
        }
    }

    private void toggleZoom(float x, float y) {
        if (image.getScaleX() > 1.01f) {
            image.animate().scaleX(1f).scaleY(1f)
                    .translationX(0f).translationY(0f).alpha(1f)
                    .setDuration(160).start();
        } else {
            image.setPivotX(x);
            image.setPivotY(y);
            image.animate().scaleX(2f).scaleY(2f)
                    .translationX(0f).translationY(0f).alpha(1f)
                    .setDuration(160).start();
        }
    }

    private void chooseSaveTarget() {
        if (original == null) return;
        String name = getIntent().getStringExtra(EXTRA_NAME);
        if (name == null || name.trim().isEmpty()) name = "tide-image.jpg";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(intent, REQ_SAVE);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的保存位置", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SAVE || resultCode != RESULT_OK
                || data == null || data.getData() == null || original == null) {
            return;
        }
        Uri uri = data.getData();
        new Thread(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("无法写入");
                out.write(original);
                out.flush();
                main.post(() -> Toast.makeText(this,
                        "已保存", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this,
                        "保存失败", Toast.LENGTH_SHORT).show());
            }
        }, "tide-save-image").start();
    }

    private void showError(String message) {
        progress.setVisibility(View.GONE);
        image.setVisibility(View.GONE);
        error.setText(message);
        error.setVisibility(View.VISIBLE);
        save.setEnabled(false);
        save.setAlpha(0.45f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static Bitmap decode(byte[] bytes, int max) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > max * 2) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        super.onDestroy();
    }
}
