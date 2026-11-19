package com.whaleeye.tide;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 表情面板的 4 列网格适配器。只依赖框架，不引 RecyclerView。 */
public final class StickerAdapter extends BaseAdapter {
    private final Context context;
    private final TideClient client;
    private final int cellSize;
    private final List<Sticker> items = new ArrayList<>();
    private final LruCache<String, Bitmap> cache;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    public StickerAdapter(Context context, TideClient client, int cellSize) {
        this.context = context;
        this.client = client;
        this.cellSize = Math.max(1, cellSize);
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        this.cache = new LruCache<String, Bitmap>(Math.max(1024, maxKb / 8)) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return Math.max(1, value.getByteCount() / 1024);
            }
        };
    }

    public void setItems(List<Sticker> next) {
        items.clear();
        if (next != null) items.addAll(next);
        notifyDataSetChanged();
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Sticker getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ImageView view = convertView instanceof ImageView
                ? (ImageView) convertView : new ImageView(context);
        view.setLayoutParams(new AbsListView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, cellSize));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setBackgroundResource(R.drawable.bg_sticker_cell);
        int pad = dp(6);
        view.setPadding(pad, pad, pad, pad);
        Sticker sticker = items.get(position);
        view.setTag(sticker.attachmentId);
        Bitmap cached = cache.get(sticker.attachmentId);
        if (cached != null) {
            view.setImageBitmap(cached);
        } else {
            view.setImageDrawable(null);
            load(sticker, view);
        }
        return view;
    }

    private void load(Sticker sticker, ImageView target) {
        executor.execute(() -> {
            try {
                byte[] bytes = client.downloadAttachment(sticker.attachmentId);
                Bitmap bitmap = decode(bytes);
                if (bitmap == null) return;
                cache.put(sticker.attachmentId, bitmap);
                main.post(() -> {
                    if (sticker.attachmentId.equals(target.getTag())) {
                        target.setImageBitmap(bitmap);
                    }
                });
            } catch (Exception ignored) {
                // 单张表情加载失败不影响其它格子。
            }
        });
    }

    private static Bitmap decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (max / sample > 1024) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
