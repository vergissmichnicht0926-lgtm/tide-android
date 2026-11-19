package com.whaleeye.tide;

import android.text.TextUtils;

import org.json.JSONObject;

/** 登录/注册/设备审批成功后，统一把 token 与身份写入本地会话。 */
final class AuthSession {
    private AuthSession() {
    }

    static String apply(Session session, JSONObject response, String username) {
        String access = firstNonNull(response, "access_token", "accessToken");
        String refresh = firstNonNull(response, "refresh_token", "refreshToken");
        String deviceId = firstNonNull(response, "device_id", "deviceId");
        if (access == null) throw new IllegalStateException("网关没返回 access_token");
        session.saveLogin(username, access, refresh, deviceId);
        String userId = firstNonNull(response, "userId", "user_id");
        if (userId != null) session.saveUserId(userId);
        String role = response.optString("role", "guest");
        if (role.isEmpty() || "null".equals(role)) role = "guest";
        String displayName = response.optString("displayName", username);
        if (displayName.isEmpty() || "null".equals(displayName)) displayName = username;
        session.saveIdentity(role, displayName);
        session.setDeviceTrustEnrolled(true);
        String conversationId = firstNonNull(
                response, "conversationId", "conversation_id");
        if (!TextUtils.isEmpty(conversationId)) session.setConversationId(conversationId);
        return conversationId;
    }

    private static String firstNonNull(JSONObject object, String first, String second) {
        String value = object.optString(first, null);
        if (value == null || value.isEmpty() || "null".equals(value)) {
            value = object.optString(second, null);
        }
        if (value == null || value.isEmpty() || "null".equals(value)) return null;
        return value;
    }
}
