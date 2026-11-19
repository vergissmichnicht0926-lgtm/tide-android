package com.whaleeye.tide;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/** 三个顶层页共享的底栏，带选中态和 CLEAR_TOP 切换。 */
public final class BottomNav {
    public static final int TAB_MESSAGES = 0;
    public static final int TAB_MOMENTS = 1;
    public static final int TAB_MANAGE = 2;

    private BottomNav() {
    }

    public static void bind(Activity activity, int selected) {
        Session session = new Session(activity);
        View messages = activity.findViewById(R.id.nav_messages);
        View moments = activity.findViewById(R.id.nav_moments);
        View manage = activity.findViewById(R.id.nav_manage);
        View iconMessages = activity.findViewById(R.id.icon_messages);
        View iconMoments = activity.findViewById(R.id.icon_moments);
        View iconManage = activity.findViewById(R.id.icon_manage);
        TextView labelMessages = activity.findViewById(R.id.label_messages);
        TextView labelMoments = activity.findViewById(R.id.label_moments);
        TextView labelManage = activity.findViewById(R.id.label_manage);

        boolean admin = session.isAdmin();
        manage.setVisibility(admin ? View.VISIBLE : View.GONE);
        if (!admin && selected == TAB_MANAGE) selected = TAB_MESSAGES;

        style(activity, iconMessages, labelMessages, selected == TAB_MESSAGES);
        style(activity, iconMoments, labelMoments, selected == TAB_MOMENTS);
        style(activity, iconManage, labelManage, selected == TAB_MANAGE);

        final int current = selected;
        messages.setOnClickListener(v -> open(activity, current, TAB_MESSAGES));
        moments.setOnClickListener(v -> open(activity, current, TAB_MOMENTS));
        manage.setOnClickListener(v -> {
            if (admin) open(activity, current, TAB_MANAGE);
        });
    }

    private static void style(Activity activity, View icon, TextView label, boolean active) {
        int color = activity.getResources().getColor(active
                ? R.color.teal : R.color.ink_3);
        if (icon instanceof ImageView) {
            ((ImageView) icon).setImageTintList(ColorStateList.valueOf(color));
        }
        label.setTextColor(color);
        icon.setSelected(active);
        label.setSelected(active);
    }

    private static void open(Activity activity, int current, int target) {
        if (current == target) return;
        Class<?> destination = target == TAB_MESSAGES
                ? MessagesActivity.class
                : target == TAB_MOMENTS
                ? MomentActivity.class
                : HomeActivity.class;
        Intent intent = new Intent(activity, destination)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);
    }
}
