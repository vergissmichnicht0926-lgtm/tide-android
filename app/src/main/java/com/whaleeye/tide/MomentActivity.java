package com.whaleeye.tide;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 动态：私聊 bot 发布的时间线，带点赞。 */
public final class MomentActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<String> liking = new HashSet<>();
    private ListView list;
    private ProgressBar progress;
    private TextView empty;
    private TextView error;
    private MomentAdapter adapter;
    private TideClient client;
    private boolean loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.apply(this);
        setContentView(R.layout.activity_moment);
        client = new TideClient(new Session(this));
        list = findViewById(R.id.list_moments);
        progress = findViewById(R.id.moments_progress);
        empty = findViewById(R.id.moments_empty);
        error = findViewById(R.id.moments_error);
        adapter = new MomentAdapter(this);
        adapter.setOnLikeClickListener(this::toggleLike);
        list.setAdapter(adapter);
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.btn_refresh).setOnClickListener(v -> load());
        error.setOnClickListener(v -> load());
        BottomNav.bind(this, BottomNav.TAB_MOMENTS);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        if (loading) return;
        loading = true;
        progress.setVisibility(View.VISIBLE);
        error.setVisibility(View.GONE);
        new Thread(() -> {
            try {
                JSONObject response = client.listMoments(50);
                List<Moment> items = Moment.listFrom(response);
                main.post(() -> {
                    if (isFinishing()) return;
                    loading = false;
                    progress.setVisibility(View.GONE);
                    error.setVisibility(View.GONE);
                    adapter.setItems(items);
                    boolean isEmpty = items.isEmpty();
                    empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
                    list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (isFinishing()) return;
                    loading = false;
                    progress.setVisibility(View.GONE);
                    if (adapter.getCount() == 0) {
                        list.setVisibility(View.GONE);
                        empty.setVisibility(View.GONE);
                        error.setText(getString(R.string.moment_load_failed)
                                + " · " + getString(R.string.messages_retry));
                        error.setVisibility(View.VISIBLE);
                    } else {
                        Toast.makeText(this, R.string.moment_load_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "tide-moments").start();
    }

    private void toggleLike(Moment moment) {
        if (moment == null || liking.contains(moment.id)) return;
        liking.add(moment.id);
        final boolean target = !moment.liked;
        final int previousLikes = moment.likes;
        final boolean previousLiked = moment.liked;
        moment.liked = target;
        moment.likes = Math.max(0, previousLikes + (target ? 1 : -1));
        adapter.notifyDataSetChanged();
        new Thread(() -> {
            try {
                JSONObject response = target
                        ? client.likeMoment(moment.id)
                        : client.unlikeMoment(moment.id);
                main.post(() -> {
                    liking.remove(moment.id);
                    moment.liked = response.optBoolean("liked", target);
                    moment.likes = response.optInt("likes", moment.likes);
                    adapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                main.post(() -> {
                    liking.remove(moment.id);
                    moment.liked = previousLiked;
                    moment.likes = previousLikes;
                    adapter.notifyDataSetChanged();
                    if (!isFinishing()) {
                        Toast.makeText(this, R.string.moment_like_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }, "tide-moment-like").start();
    }
}
