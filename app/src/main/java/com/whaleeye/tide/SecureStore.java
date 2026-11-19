package com.whaleeye.tide;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureStore {
    private static final String PREFIX = "v1:";
    static final String LEGACY_ALIAS = "tide.session.v1";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    private SecureStore() {
    }

    public static boolean isEncrypted(String value) {
        return value != null && (value.startsWith(PREFIX) || KeyVault.isV2(value));
    }

    public static String encrypt(String plain) throws GeneralSecurityException {
        if (KeyVault.isReady()) return KeyVault.encrypt(plain);
        // 解锁流程完成前不应该写凭据；保留旧路径只给迁移和极端兜底用。
        try {
            return encryptRaw(plain);
        } catch (GeneralSecurityException first) {
            android.util.Log.w("SecureStore", "encrypt failed, regenerating key", first);
            deleteKey();
            return encryptRaw(plain);
        }
    }

    private static String encryptRaw(String plain) throws GeneralSecurityException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateLegacyKey());
            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(plain.getBytes("UTF-8"));
            byte[] packed = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
            return PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new GeneralSecurityException(e);
        }
    }

    public static String decrypt(String value) throws GeneralSecurityException {
        if (!isEncrypted(value)) return value;
        if (KeyVault.isV2(value)) return KeyVault.decrypt(value);
        return decryptLegacy(value);
    }

    /** 0.5.0 的 v1 密文只用于一次性迁移。 */
    static String decryptLegacy(String value) throws GeneralSecurityException {
        if (!value.startsWith(PREFIX)) throw new GeneralSecurityException("不是 v1 密文");
        try {
            byte[] packed = Base64.decode(value.substring(PREFIX.length()), Base64.NO_WRAP);
            if (packed.length <= 12) throw new GeneralSecurityException("密文长度异常");
            byte[] iv = new byte[12];
            byte[] ciphertext = new byte[packed.length - 12];
            System.arraycopy(packed, 0, iv, 0, iv.length);
            System.arraycopy(packed, iv.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateLegacyKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new GeneralSecurityException(e);
        }
    }

    public static void deleteKey() {
        deleteAlias(LEGACY_ALIAS);
    }

    static void deleteAlias(String alias) {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias);
        } catch (Exception ignored) {
        }
    }

    private static SecretKey getOrCreateLegacyKey() throws GeneralSecurityException {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            java.security.Key existing = keyStore.getKey(LEGACY_ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;

            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                    LEGACY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256);
            generator.init(builder.build());
            return generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException(e);
        }
    }
}
