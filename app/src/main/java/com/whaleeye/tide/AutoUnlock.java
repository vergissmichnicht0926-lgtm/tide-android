package com.whaleeye.tide;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 应用锁关闭时，用不需用户认证的 Keystore 密钥自动解开数据密钥。 */
final class AutoUnlock {
    private static final String PREFS = "tide.auto_unlock";
    private static final String K_WRAP = "wrap";
    private static final String ALIAS = "tide.auto.v1";

    private AutoUnlock() {
    }

    static boolean tryUnlock(Context context) {
        if (LockManager.isLockEnabled(context)) return false;
        String raw = prefs(context).getString(K_WRAP, "");
        if (raw == null || raw.isEmpty()) return false;
        try {
            String[] parts = raw.split(":", 2);
            if (parts.length != 2) return false;
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, loadKey(), new GCMParameterSpec(128, iv));
            byte[] dek = cipher.doFinal(encrypted);
            LockManager.unlockWithDataKey(context, dek);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static void disable(Context context) throws Exception {
        byte[] dek = LockManager.copyDataKey();
        if (dek == null) throw new IllegalStateException("本地密钥不可用");
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(dek);
        prefs(context).edit().putString(K_WRAP,
                Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                        + Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit();
        LockManager.setLockEnabled(context, false);
    }

    static void enable(Context context) {
        prefs(context).edit().remove(K_WRAP).commit();
        deleteKey();
        LockManager.setLockEnabled(context, true);
    }

    static void clear(Context context) {
        prefs(context).edit().clear().commit();
        deleteKey();
    }

    static void deleteKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS);
        } catch (Exception ignored) {
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(ALIAS)) {
            return (SecretKey) keyStore.getKey(ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private static SecretKey loadKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        return (SecretKey) keyStore.getKey(ALIAS, null);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
