package com.whaleeye.tide;

import android.app.Activity;
import android.content.Context;
import android.os.Build;

/**
 * 平台 BiometricPrompt 的版本隔离层。API 24~27 只有 PIN，28+ 才加载 BioGate28。
 */
public final class BioGate {
    public interface Callback {
        void onSuccess();
        void onCancelled();
        /** 临时失败：硬件忙、锁屏凭据暂时不可用等，PIN 仍可用。 */
        void onFailure(String message);
        /** key 已失效或被删除，需要 PIN 解锁后重新绑定。 */
        void onPermanentFailure(String message);
    }

    private BioGate() {
    }

    public static boolean isSupported(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && BioGate28.isSupported(context);
    }

    public static boolean isEnabled(Context context) {
        return isSupported(context)
                && LockManager.isBioEnabled(context)
                && LockManager.hasBioWrap(context);
    }

    public static boolean needsRebind(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                && LockManager.bioWasEnabled(context)
                && !isEnabled(context);
    }

    public static String reason(Context context) {
        return LockManager.bioReason(context);
    }

    public static void authenticate(Activity activity, Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            callback.onPermanentFailure("当前系统版本不支持生物识别，请使用 PIN");
            return;
        }
        BioGate28.authenticate(activity, callback);
    }

    /** PIN 解锁后，用新的 auth-bound key 重新包裹内存里的数据密钥。 */
    public static void bind(Activity activity, Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            callback.onPermanentFailure("当前系统版本不支持生物识别，请使用 PIN");
            return;
        }
        BioGate28.bind(activity, callback);
    }

    public static void cancel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) BioGate28.cancel();
    }

    public static void deleteKey() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) BioGate28.deleteKey();
    }
}
