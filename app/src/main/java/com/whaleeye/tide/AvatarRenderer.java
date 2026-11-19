package com.whaleeye.tide;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.TextView;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** 把本机头像/ AI data URI 画成 TextView 背景，避免为每个气泡再加一层 ImageView。 */
final class AvatarRenderer {
    private static final int CACHE_SIZE = 24;
    private static final Map<String, Bitmap> CACHE =
            new LinkedHashMap<String, Bitmap>(CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    return size() > CACHE_SIZE;
                }
            };

    private AvatarRenderer() {
    }

    static void applyUserCircle(TextView view, int sizeDp, String fallback) {
        Context context = view.getContext().getApplicationContext();
        String uri = ProfileStore.avatarUri(context);
        Bitmap source = loadUser(context, uri);
        Bitmap shaped = source == null ? null : shape(
                "u|" + uri + '|' + sizeDp + "|c", source, dp(view, sizeDp), 0, true);
        if (shaped != null) {
            view.setText("");
            view.setBackground(new BitmapDrawable(view.getResources(), shaped));
        } else {
            view.setText(TextUtils.isEmpty(fallback) ? "我" : fallback);
            view.setBackgroundResource(R.drawable.bg_avatar_teal);
        }
    }

    static void applyUserSquare(TextView view, int sizeDp, String fallback) {
        Context context = view.getContext().getApplicationContext();
        String uri = ProfileStore.avatarUri(context);
        Bitmap source = loadUser(context, uri);
        Bitmap shaped = source == null ? null : shape(
                "u|" + uri + '|' + sizeDp + "|s", source, dp(view, sizeDp), dp(view, 14), false);
        if (shaped != null) {
            view.setText("");
            view.setBackground(new BitmapDrawable(view.getResources(), shaped));
        } else {
            view.setText(TextUtils.isEmpty(fallback) ? "我" : fallback);
            view.setBackgroundResource(R.drawable.bg_avatar_square_teal);
        }
    }

    static void applyAiCircle(TextView view, int sizeDp) {
        Context context = view.getContext().getApplicationContext();
        String key = AiProfileStore.avatar(context);
        Bitmap source = AiProfileStore.avatarBitmap(context);
        Bitmap shaped = source == null ? null : shape(
                "a|" + key + '|' + sizeDp + "|c", source, dp(view, sizeDp), 0, true);
        if (shaped != null) {
            view.setText("");
            view.setBackground(new BitmapDrawable(view.getResources(), shaped));
        } else {
            view.setText(AiProfileStore.initial(context, "汐"));
            view.setBackgroundResource(R.drawable.bg_avatar_soft);
        }
    }

    static void applyAiSquare(TextView view, int sizeDp) {
        Context context = view.getContext().getApplicationContext();
        String key = AiProfileStore.avatar(context);
        Bitmap source = AiProfileStore.avatarBitmap(context);
        Bitmap shaped = source == null ? null : shape(
                "a|" + key + '|' + sizeDp + "|s", source, dp(view, sizeDp), dp(view, 14), false);
        if (shaped != null) {
            view.setText("");
            view.setBackground(new BitmapDrawable(view.getResources(), shaped));
        } else {
            view.setText(AiProfileStore.initial(context, "汐"));
            view.setBackgroundResource(R.drawable.bg_avatar_square_teal);
        }
    }

    static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static Bitmap loadUser(Context context, String uri) {
        if (TextUtils.isEmpty(uri)) return null;
        Bitmap cached = cachedSource("u|" + uri);
        if (cached != null) return cached;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(uri))) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / sample > 512) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap;
            try (InputStream in = context.getContentResolver().openInputStream(Uri.parse(uri))) {
                if (in == null) return null;
                bitmap = BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap != null) {
                synchronized (CACHE) {
                    CACHE.put("u|" + uri, bitmap);
                }
            }
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap cachedSource(String key) {
        synchronized (CACHE) {
            return CACHE.get(key);
        }
    }

    static Bitmap shape(String key, Bitmap source, int size, int radius, boolean circle) {
        synchronized (CACHE) {
            Bitmap cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        if (size <= 0 || source.getWidth() <= 0 || source.getHeight() <= 0) return null;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        BitmapShader shader = new BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix matrix = new Matrix();
        float scale = Math.max(size / (float) source.getWidth(),
                size / (float) source.getHeight());
        matrix.setScale(scale, scale);
        matrix.postTranslate(
                (size - source.getWidth() * scale) / 2f,
                (size - source.getHeight() * scale) / 2f);
        shader.setLocalMatrix(matrix);
        paint.setShader(shader);
        if (circle) {
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        } else {
            canvas.drawRoundRect(new RectF(0, 0, size, size), radius, radius, paint);
        }
        synchronized (CACHE) {
            CACHE.put(key, out);
        }
        return out;
    }

    private static int dp(TextView view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
