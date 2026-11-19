package com.whaleeye.tide;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONObject;

/** AI 全局资料缓存：姓名、个签、头像 data URI。 */
final class AiProfileStore {
    private static final String PREFS = "tide.ai_profile";

    private AiProfileStore() {
    }

    static void apply(Context context, JSONObject response) {
        JSONObject profile = response.optJSONObject("profile");
        if (profile == null) return;
        String name = profile.optString("name", "");
        if (TextUtils.isEmpty(name) || "null".equals(name)) name = "汐语";
        String signature = profile.optString("signature", "");
        if ("null".equals(signature)) signature = "";
        String avatar = profile.optString("avatar", "");
        if ("null".equals(avatar)) avatar = "";
        prefs(context).edit()
                .putString("name", name)
                .putString("signature", signature)
                .putString("avatar", avatar)
                .apply();
        AvatarRenderer.clear();
    }

    static void save(Context context, String name, String signature, String avatar) {
        prefs(context).edit()
                .putString("name", TextUtils.isEmpty(name) ? "汐语" : name)
                .putString("signature", signature == null ? "" : signature)
                .putString("avatar", avatar == null ? "" : avatar)
                .apply();
        AvatarRenderer.clear();
    }

    static String name(Context context, String fallback) {
        String value = prefs(context).getString("name", "");
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    static String signature(Context context) {
        return prefs(context).getString("signature", "");
    }

    static String avatar(Context context) {
        return prefs(context).getString("avatar", "");
    }

    static String initial(Context context, String fallback) {
        String name = name(context, fallback);
        return TextUtils.isEmpty(name) ? "汐" : name.substring(0, 1);
    }

    static Bitmap avatarBitmap(Context context) {
        String value = avatar(context);
        if (TextUtils.isEmpty(value) || !value.startsWith("data:image/")) return null;
        int comma = value.indexOf(',');
        if (comma < 0) return null;
        try {
            byte[] bytes = Base64.decode(value.substring(comma + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
