package com.whaleeye.tide;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 会话内搜索：输入关键词，点结果回到聊天页定位。 */
public final class SearchActivity extends Activity {
    public static final String EXTRA_CONVERSATION_ID = "conversation_id";
    public static final String EXTRA_MESSAGE_ID = "message_id";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Message> items = new ArrayList<>();
    private TideClient client;
    private String conversationId;
    private String selfUserId;
    private EditText input;
    private ListView list;
    private TextView empty;
    private Adapter adapter;
    private int requestSeq = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TideApp.ensureUnlocked(this)) return;
        SecureWindow.apply(this);
        conversationId = getIntent().getStringExtra(EXTRA_CONVERSATION_ID);
        if (TextUtils.isEmpty(conversationId)) {
            finish();
            return;
        }
        client = new TideClient(new Session(this));
        selfUserId = new Session(this).userId();
        setContentView(R.layout.activity_search);
        input = findViewById(R.id.search_input);
        list = findViewById(R.id.search_results);
        empty = findViewById(R.id.search_empty);
        adapter = new Adapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Message message = adapter.getItem(position);
            Intent result = new Intent();
            result.putExtra(EXTRA_MESSAGE_ID, message.id);
            setResult(RESULT_OK, result);
            finish();
        });
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                final String query = s.toString().trim();
                main.removeCallbacks(searchRunnable);
                if (query.isEmpty()) {
                    items.clear();
                    adapter.notifyDataSetChanged();
                    empty.setVisibility(View.GONE);
                    return;
                }
                pendingQuery = query;
                main.postDelayed(searchRunnable, 350L);
            }
        });
    }

    private String pendingQuery;
    private final Runnable searchRunnable = () -> search(pendingQuery);

    private void search(String query) {
        final int token = ++requestSeq;
        new Thread(() -> {
            try {
                JSONObject response = client.searchMessages(conversationId, query, 50);
                List<Message> found = new ArrayList<>();
                org.json.JSONArray array = TideClient.messagesOf(response);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject object = array.optJSONObject(i);
                    if (object != null) found.add(Message.fromJson(object));
                }
                main.post(() -> {
                    if (token != requestSeq || isFinishing()) return;
                    items.clear();
                    items.addAll(found);
                    adapter.notifyDataSetChanged();
                    empty.setVisibility(found.isEmpty() ? View.VISIBLE : View.GONE);
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (token != requestSeq || isFinishing()) return;
                    empty.setText(getString(R.string.chat_search_failed)
                            + "：" + friendly(e));
                    empty.setVisibility(View.VISIBLE);
                });
            }
        }, "tide-search").start();
    }

    private static String friendly(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private final class Adapter extends BaseAdapter {
        private final LayoutInflater inflater;

        Adapter(Context context) {
            inflater = LayoutInflater.from(context);
        }

        @Override public int getCount() { return items.size(); }
        @Override public Message getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = inflater.inflate(R.layout.item_search_result, parent, false);
            }
            Message message = getItem(position);
            TextView sender = view.findViewById(R.id.search_sender);
            TextView text = view.findViewById(R.id.search_text);
            TextView time = view.findViewById(R.id.search_time);
            boolean mine = message.isUser()
                    && message.senderUserId != null
                    && message.senderUserId.equals(selfUserId);
            sender.setText(message.isUser()
                    ? (TextUtils.isEmpty(message.senderName) ? "成员" : message.senderName)
                    : AiProfileStore.name(SearchActivity.this, "汐语"));
            sender.setTextColor(getResources().getColor(mine ? R.color.teal_dark : R.color.ink_2));
            text.setText(message.text);
            time.setText(Moment.timeLabel(message.createdAt, System.currentTimeMillis()));
            return view;
        }
    }
}
