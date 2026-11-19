package com.whaleeye.tide;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 动态列表：作者、正文、时间、点赞。 */
public final class MomentAdapter extends BaseAdapter {
    public interface OnLikeClickListener {
        void onLikeClick(Moment moment);
    }

    private final Context context;
    private final LayoutInflater inflater;
    private final List<Moment> items = new ArrayList<>();
    private OnLikeClickListener likeListener;

    public MomentAdapter(Context context) {
        this.context = context.getApplicationContext();
        this.inflater = LayoutInflater.from(context);
    }

    public void setOnLikeClickListener(OnLikeClickListener listener) {
        likeListener = listener;
    }

    public void setItems(List<Moment> moments) {
        items.clear();
        if (moments != null) items.addAll(moments);
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public Moment getItem(int position) {
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
            view = inflater.inflate(R.layout.item_moment, parent, false);
            holder = new Holder();
            holder.avatar = view.findViewById(R.id.moment_avatar);
            holder.author = view.findViewById(R.id.moment_author);
            holder.text = view.findViewById(R.id.moment_text);
            holder.time = view.findViewById(R.id.moment_time);
            holder.like = view.findViewById(R.id.moment_like);
            view.setTag(holder);
        } else {
            holder = (Holder) view.getTag();
        }
        Moment item = getItem(position);
        AvatarRenderer.applyAiCircle(holder.avatar, 42);
        holder.author.setText(AiProfileStore.name(context, item.authorName));
        holder.text.setText(item.text);
        holder.time.setText(Moment.timeLabel(item.createdAt, System.currentTimeMillis()));

        int color = context.getResources().getColor(item.liked ? R.color.badge : R.color.ink_3);
        holder.like.setText(item.likes > 0 ? String.valueOf(item.likes)
                : context.getString(item.liked ? R.string.moment_liked : R.string.moment_like));
        holder.like.setTextColor(color);
        holder.like.setCompoundDrawableTintList(ColorStateList.valueOf(color));
        holder.like.setContentDescription(context.getString(
                item.liked ? R.string.moment_unlike : R.string.moment_like));
        holder.like.setOnClickListener(v -> {
            if (likeListener != null) likeListener.onLikeClick(item);
        });
        return view;
    }

    private static final class Holder {
        TextView avatar;
        TextView author;
        TextView text;
        TextView time;
        TextView like;
    }
}
