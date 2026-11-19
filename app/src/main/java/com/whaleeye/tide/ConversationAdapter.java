package com.whaleeye.tide;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 会话列表。零依赖，用 BaseAdapter 手写。 */
public final class ConversationAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final Context context;
    private final List<Conversation> items = new ArrayList<>();

    public ConversationAdapter(Context context) {
        this.context = context;
        this.inflater = LayoutInflater.from(context);
    }

    public void setItems(List<Conversation> conversations) {
        items.clear();
        if (conversations != null) items.addAll(conversations);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Conversation getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = convertView;
        Holder holder;
        if (view == null) {
            view = inflater.inflate(R.layout.item_conversation, parent, false);
            holder = new Holder();
            holder.avatar = view.findViewById(R.id.conv_avatar);
            holder.title = view.findViewById(R.id.conv_title);
            holder.preview = view.findViewById(R.id.conv_preview);
            holder.time = view.findViewById(R.id.conv_time);
            holder.badge = view.findViewById(R.id.conv_badge);
            holder.notice = view.findViewById(R.id.conv_notice);
            view.setTag(holder);
        } else {
            holder = (Holder) view.getTag();
        }
        Conversation item = getItem(position);
        if (item.isGroup()) {
            holder.avatar.setText(item.initial());
            holder.avatar.setBackgroundResource(R.drawable.bg_avatar_square_soft);
        } else {
            AvatarRenderer.applyAiSquare(holder.avatar, 48);
        }
        holder.title.setText(item.isGroup()
                ? item.title
                : AiProfileStore.name(context, "汐语"));
        holder.preview.setText(item.preview());
        holder.time.setText(item.timeLabel());
        boolean notice = item.announcement != null && !item.announcement.trim().isEmpty();
        holder.notice.setVisibility(notice ? View.VISIBLE : View.GONE);
        if (item.unread > 0) {
            holder.badge.setVisibility(View.VISIBLE);
            holder.badge.setText(item.unread > 99 ? "99+" : String.valueOf(item.unread));
        } else {
            holder.badge.setVisibility(View.GONE);
        }
        return view;
    }

    private static final class Holder {
        TextView avatar;
        TextView title;
        TextView preview;
        TextView time;
        TextView badge;
        TextView notice;
    }
}
