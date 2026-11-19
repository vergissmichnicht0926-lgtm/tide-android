package com.whaleeye.tide;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/** 本机显示资料：昵称、签名、头像 URI。不改服务端账号。 */
final class ProfileStore {
    private static final String PREFS = "tide.profile";
    private static final String K_NAME = "display_name";
    private static final String K_SIGNATURE = "signature";
    private static final String K_AVATAR = "avatar_uri";

    private ProfileStore() {
    }

    static String displayName(Context context, String fallback) {
        String value = prefs(context).getString(K_NAME, "");
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    static String signature(Context context) {
        return prefs(context).getString(K_SIGNATURE, "");
    }

    static String avatarUri(Context context) {
        return prefs(context).getString(K_AVATAR, "");
    }

    static void save(Context context, String name, String signature, String avatarUri) {
        prefs(context).edit()
                .putString(K_NAME, name == null ? "" : name)
                .putString(K_SIGNATURE, signature == null ? "" : signature)
                .putString(K_AVATAR, avatarUri == null ? "" : avatarUri)
                .apply();
        AvatarRenderer.clear();
    }

    static String initial(Context context, String fallback) {
        String value = displayName(context, fallback);
        return TextUtils.isEmpty(value) ? "汐" : value.substring(0, 1);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
