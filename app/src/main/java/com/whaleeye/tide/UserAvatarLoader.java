package com.whaleeye.tide;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 成员头像：按 user_id + avatar_version 拉取一次，命中缓存后直接换背景。 */
final class UserAvatarLoader {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "tide-user-avatar");
        thread.setDaemon(true);
        return thread;
    });
    private static final android.util.LruCache<String, Bitmap> CACHE =
            new android.util.LruCache<String, Bitmap>(64) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };
    private static final Set<String> INFLIGHT =
            Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> MISSING =
            Collections.synchronizedSet(new HashSet<String>());

    private UserAvatarLoader() {
    }

    static void apply(TextView view, int sizeDp, String userId, String version,
                      boolean circle) {
        if (view == null || TextUtils.isEmpty(userId)) return;
        final String identity = userId + "|" + (version == null ? "*" : version);
        final String key = identity + "|" + sizeDp + (circle ? "c" : "s");
        view.setTag(key);
        Bitmap cached = CACHE.get(key);
        if (cached != null) {
            setBitmap(view, cached);
            return;
        }
        if (MISSING.contains(identity)) return;
        if (!INFLIGHT.add(key)) return;
        final Context app = view.getContext().getApplicationContext();
        final String viewKey = key;
        EXEC.execute(() -> {
            try {
                TideClient client = new TideClient(new Session(app));
                byte[] bytes = client.downloadUserAvatar(userId);
                Bitmap source = decode(bytes, sizeDp * 3);
                if (source == null) throw new IllegalStateException("avatar decode failed");
                Bitmap shaped = AvatarRenderer.shape(
                        "remote|" + identity + "|" + sizeDp + "|" + circle,
                        source,
                        Math.round(sizeDp * app.getResources().getDisplayMetrics().density),
                        circle ? 0 : Math.round(14 * app.getResources().getDisplayMetrics().density),
                        circle);
                if (shaped == null) throw new IllegalStateException("avatar shape failed");
                CACHE.put(viewKey, shaped);
                MAIN.post(() -> {
                    INFLIGHT.remove(viewKey);
                    if (viewKey.equals(view.getTag())) setBitmap(view, shaped);
                });
            } catch (Exception e) {
                MISSING.add(identity);
                MAIN.post(() -> INFLIGHT.remove(viewKey));
            }
        });
    }

    private static void setBitmap(TextView view, Bitmap bitmap) {
        view.setText("");
        view.setBackground(new BitmapDrawable(view.getResources(), bitmap));
    }

    private static Bitmap decode(byte[] bytes, int maxDimension) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(new ByteArrayInputStream(bytes), null, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(bounds.outWidth / sample, bounds.outHeight / sample) > maxDimension * 2) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap decoded = BitmapFactory.decodeStream(
                new ByteArrayInputStream(bytes), null, options);
        if (decoded == null) return null;
        int side = Math.max(decoded.getWidth(), decoded.getHeight());
        if (side <= maxDimension) return decoded;
        float scale = maxDimension / (float) side;
        Bitmap scaled = Bitmap.createScaledBitmap(
                decoded,
                Math.max(1, Math.round(decoded.getWidth() * scale)),
                Math.max(1, Math.round(decoded.getHeight() * scale)),
                true);
        if (scaled != decoded) decoded.recycle();
        return scaled;
    }
}
