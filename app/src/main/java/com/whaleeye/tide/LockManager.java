package com.whaleeye.tide;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.AEADBadTagException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 应用锁与本地密钥门。
 *
 * 数据密钥只在解锁后进内存；PIN 和生物识别各持一条包裹，谁先通过就装谁。
 * 5 次错误只擦本地，不碰服务端。
 */
public final class LockManager {
    public enum UnlockResult {
        OK,
        WRONG,
        WIPED,
        /** PIN 对了，但本地包裹损坏/被替换；需要清除本地数据后重新登录。 */
        KEY_LOST
    }

    private static final String PREFS = "tide.lock";
    private static final String K_SALT = "pin_salt";
    private static final String K_HASH = "pin_hash";
    private static final String K_FAILS = "pin_fails";
    private static final String K_VAULT_VERSION = "vault_version";
    private static final String K_VAULT_SALT = "vault_pin_salt";
    private static final String K_PIN_WRAP = "vault_pin_wrap";
    private static final String K_BIO_WRAP = "vault_bio_wrap";
    private static final String K_BIO_ENABLED = "bio_enabled";
    private static final String K_BIO_WAS_ENABLED = "bio_was_enabled";
    private static final String K_BIO_REASON = "bio_reason";
    private static final String K_MIGRATION_LOST = "migration_lost";
    private static final String K_LOCK_ENABLED = "lock_enabled";
    private static final int MAX_FAILS = 5;
    private static final int ITERATIONS = 120_000;
    private static final byte[] AAD_PIN = "tide-pin-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static volatile boolean uiUnlocked = false;

    private LockManager() {
    }

    public static boolean isUnlocked() {
        return LockPolicy.canShowUi(uiUnlocked, KeyVault.isReady());
    }

    public static boolean hasSessionKey() {
        return LockPolicy.canUseCredentials(KeyVault.isReady());
    }

    /** 只锁界面，保留数据密钥，让前台服务继续持有 SSE。 */
    public static void lockUi() {
        uiUnlocked = false;
    }

    /** 硬安全动作：清掉数据密钥。 */
    public static void lockAndWipe() {
        uiUnlocked = false;
        KeyVault.clear();
    }

    /** 兼容旧调用；新代码必须显式选择 lockUi 或 lockAndWipe。 */
    public static void lock() {
        lockAndWipe();
    }

    public static boolean isConfigured(Context context) {
        SharedPreferences prefs = prefs(context);
        return prefs.contains(K_SALT) && prefs.contains(K_HASH);
    }

    public static boolean isLockEnabled(Context context) {
        return prefs(context).getBoolean(K_LOCK_ENABLED, true);
    }

    static void setLockEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(K_LOCK_ENABLED, enabled).commit();
    }

    public static int failures(Context context) {
        return prefs(context).getInt(K_FAILS, 0);
    }

    /** 首次设置 PIN：同时生成数据密钥，并用 PIN 与（稍后的）生物识别各包一份。 */
    public static void configure(Context context, String pin) {
        if (pin == null || !pin.matches("\\d{6}")) {
            throw new IllegalArgumentException("PIN 必须是 6 位数字");
        }
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] hash = derive(pin, salt);
        byte[] dek = CryptoBox.random(CryptoBox.KEY_BYTES);
        byte[] vaultSalt = CryptoBox.random(16);
        try {
            SecretKey kek = CryptoBox.pinKek(pin.toCharArray(), vaultSalt);
            byte[] wrapped = CryptoBox.seal(kek, dek, AAD_PIN);
            KeyVault.install(dek);
            prefs(context).edit()
                    .putString(K_SALT, b64(salt))
                    .putString(K_HASH, b64(hash))
                    .putString(K_VAULT_SALT, b64(vaultSalt))
                    .putString(K_PIN_WRAP, b64(wrapped))
                    .putInt(K_FAILS, 0)
                    .putBoolean(K_LOCK_ENABLED, true)
                    .remove(K_VAULT_VERSION)
                    .remove(K_BIO_WRAP)
                    .remove(K_BIO_ENABLED)
                    .remove(K_BIO_WAS_ENABLED)
                    .remove(K_BIO_REASON)
                    .remove(K_MIGRATION_LOST)
                    .commit();
            BioGate.deleteKey();
            AutoUnlock.clear(context);
            uiUnlocked = true;
            migrateLegacyQuietly(context);
            TideService.onUiUnlocked();
        } catch (GeneralSecurityException e) {
            KeyVault.clear();
            throw new IllegalStateException("本地密钥初始化失败", e);
        }
    }

    public static UnlockResult unlock(Context context, String pin) {
        SharedPreferences prefs = prefs(context);
        String saltRaw = prefs.getString(K_SALT, null);
        String hashRaw = prefs.getString(K_HASH, null);
        if (saltRaw == null || hashRaw == null) return UnlockResult.WRONG;
        byte[] expected = Base64.decode(hashRaw, Base64.NO_WRAP);
        byte[] actual = derive(pin == null ? "" : pin, Base64.decode(saltRaw, Base64.NO_WRAP));
        if (expected.length != actual.length || !MessageDigest.isEqual(expected, actual)) {
            int fails = prefs.getInt(K_FAILS, 0) + 1;
            prefs.edit().putInt(K_FAILS, fails).apply();
            if (fails >= MAX_FAILS) {
                wipeLocal(context);
                return UnlockResult.WIPED;
            }
            return UnlockResult.WRONG;
        }

        try {
            byte[] dek = unwrapWithPin(prefs, pin);
            if (dek == null) {
                if (prefs.getInt(K_VAULT_VERSION, 0) >= 2) {
                    return UnlockResult.KEY_LOST;
                }
                // 0.5.0 升级：还没有数据密钥包裹，现生成一把再迁移旧 v1 密文。
                dek = CryptoBox.random(CryptoBox.KEY_BYTES);
                byte[] vaultSalt = CryptoBox.random(16);
                SecretKey kek = CryptoBox.pinKek(pin.toCharArray(), vaultSalt);
                byte[] wrapped = CryptoBox.seal(kek, dek, AAD_PIN);
                prefs.edit()
                        .putString(K_VAULT_SALT, b64(vaultSalt))
                        .putString(K_PIN_WRAP, b64(wrapped))
                        .commit();
            }
            KeyVault.install(dek);
        } catch (AEADBadTagException e) {
            return UnlockResult.KEY_LOST;
        } catch (GeneralSecurityException e) {
            return UnlockResult.KEY_LOST;
        }

        prefs.edit().putInt(K_FAILS, 0).apply();
        uiUnlocked = true;
        migrateLegacyQuietly(context);
        TideService.onUiUnlocked();
        return UnlockResult.OK;
    }

    /** 生物识别 CryptoObject 解出数据密钥后走这里；不碰 PIN 失败计数。 */
    static void unlockWithDataKey(Context context, byte[] dek) throws GeneralSecurityException {
        if (dek == null || dek.length != CryptoBox.KEY_BYTES) {
            throw new GeneralSecurityException("数据密钥长度异常");
        }
        KeyVault.install(dek);
        prefs(context).edit().putInt(K_FAILS, 0).apply();
        uiUnlocked = true;
        migrateLegacyQuietly(context);
        TideService.onUiUnlocked();
    }

    public static void wipeLocal(Context context) {
        uiUnlocked = false;
        KeyVault.clear();
        TideService.stop(context);
        new Session(context).clearLogin();
        SecureStore.deleteAlias(SecureStore.LEGACY_ALIAS);
        SecureStore.deleteAlias("tide.bio.v1");
        BioGate.deleteKey();
        AutoUnlock.clear(context);
        prefs(context).edit().clear().commit();
    }

    public static int migrationLostCount(Context context) {
        return prefs(context).getInt(K_MIGRATION_LOST, 0);
    }

    static byte[] copyDataKey() {
        return KeyVault.copyKey();
    }

    static boolean isBioEnabled(Context context) {
        return prefs(context).getBoolean(K_BIO_ENABLED, false);
    }

    static boolean bioWasEnabled(Context context) {
        return prefs(context).getBoolean(K_BIO_WAS_ENABLED, false);
    }

    static void setBioEnabled(Context context, boolean enabled) {
        SharedPreferences.Editor e = prefs(context).edit().putBoolean(K_BIO_ENABLED, enabled);
        if (enabled) e.putBoolean(K_BIO_WAS_ENABLED, true);
        e.apply();
    }

    static boolean hasBioWrap(Context context) {
        return prefs(context).contains(K_BIO_WRAP);
    }

    static byte[] bioWrap(Context context) {
        String raw = prefs(context).getString(K_BIO_WRAP, null);
        if (raw == null) return null;
        try {
            return Base64.decode(raw, Base64.NO_WRAP);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static void saveBioWrap(Context context, byte[] packed) {
        prefs(context).edit()
                .putString(K_BIO_WRAP, b64(packed))
                .putBoolean(K_BIO_ENABLED, true)
                .putBoolean(K_BIO_WAS_ENABLED, true)
                .remove(K_BIO_REASON)
                .commit();
    }

    static void invalidateBio(Context context, String reason) {
        BioGate.deleteKey();
        prefs(context).edit()
                .putBoolean(K_BIO_ENABLED, false)
                .putBoolean(K_BIO_WAS_ENABLED, true)
                .putString(K_BIO_REASON, reason)
                .remove(K_BIO_WRAP)
                .commit();
    }

    static void disableBio(Context context) {
        BioGate.deleteKey();
        prefs(context).edit()
                .putBoolean(K_BIO_ENABLED, false)
                .putBoolean(K_BIO_WAS_ENABLED, false)
                .remove(K_BIO_WRAP)
                .remove(K_BIO_REASON)
                .commit();
    }

    static void recordBioFailure(Context context, String reason) {
        BioGate.deleteKey();
        prefs(context).edit()
                .putBoolean(K_BIO_ENABLED, false)
                .putString(K_BIO_REASON, reason)
                .remove(K_BIO_WRAP)
                .commit();
    }

    static String bioReason(Context context) {
        return prefs(context).getString(K_BIO_REASON, "");
    }

    private static byte[] unwrapWithPin(SharedPreferences prefs, String pin)
            throws GeneralSecurityException {
        String wrapRaw = prefs.getString(K_PIN_WRAP, null);
        String saltRaw = prefs.getString(K_VAULT_SALT, null);
        if (wrapRaw == null || saltRaw == null) return null;
        SecretKey kek = CryptoBox.pinKek(pin.toCharArray(),
                Base64.decode(saltRaw, Base64.NO_WRAP));
        return CryptoBox.open(kek, Base64.decode(wrapRaw, Base64.NO_WRAP), AAD_PIN);
    }

    /**
     * v1 密文用 0.5.0 的旧 Keystore key 解，再用新数据密钥重新加密。
     * 解不开的条目直接移除——继续留着只会在启动时反复返回 null。
     */
    private static void migrateLegacy(Context context) {
        SharedPreferences lockPrefs = prefs(context);
        if (lockPrefs.getInt(K_VAULT_VERSION, 0) >= 2) return;
        SharedPreferences sessionPrefs = context.getApplicationContext()
                .getSharedPreferences(Session.PREFS, Context.MODE_PRIVATE);
        Map<String, ?> all = sessionPrefs.getAll();
        Map<String, String> replacements = new HashMap<>();
        int lost = 0;
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            Object value = entry.getValue();
            if (!(value instanceof String)) continue;
            String text = (String) value;
            if (!text.startsWith("v1:")) continue;
            try {
                String plain = SecureStore.decryptLegacy(text);
                replacements.put(entry.getKey(), KeyVault.encrypt(plain));
            } catch (GeneralSecurityException e) {
                replacements.put(entry.getKey(), null);
                lost++;
            }
        }
        SharedPreferences.Editor editor = sessionPrefs.edit();
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            if (entry.getValue() == null) editor.remove(entry.getKey());
            else editor.putString(entry.getKey(), entry.getValue());
        }
        editor.commit();
        lockPrefs.edit()
                .putInt(K_VAULT_VERSION, 2)
                .putInt(K_MIGRATION_LOST, lost)
                .commit();
        SecureStore.deleteAlias(SecureStore.LEGACY_ALIAS);
    }

    private static void migrateLegacyQuietly(Context context) {
        try {
            migrateLegacy(context);
        } catch (RuntimeException e) {
            android.util.Log.w("LockManager", "legacy credential migration failed", e);
        }
    }

    private static byte[] derive(String pin, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PIN 派生失败", e);
        }
    }

    private static String b64(byte[] raw) {
        return Base64.encodeToString(raw, Base64.NO_WRAP);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
