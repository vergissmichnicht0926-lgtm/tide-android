package com.whaleeye.tide;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 纯 JCE 的 AES-GCM 与 PIN 派生。刻意不碰 Android API，方便 JVM 单测。
 */
final class CryptoBox {
    static final int KEY_BYTES = 32;
    static final int IV_BYTES = 12;
    static final int TAG_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private CryptoBox() {
    }

    static byte[] random(int length) {
        byte[] out = new byte[length];
        RANDOM.nextBytes(out);
        return out;
    }

    static SecretKey aes(byte[] raw) throws GeneralSecurityException {
        if (raw == null || raw.length != KEY_BYTES) {
            throw new GeneralSecurityException("AES-256 密钥长度不正确");
        }
        return new SecretKeySpec(raw, "AES");
    }

    static SecretKey pinKek(char[] pin, byte[] salt) throws GeneralSecurityException {
        if (pin == null || pin.length == 0 || salt == null || salt.length == 0) {
            throw new GeneralSecurityException("PIN 或盐为空");
        }
        PBEKeySpec spec = new PBEKeySpec(pin, salt, PBKDF2_ITERATIONS, KEY_BYTES * 8);
        try {
            byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                    .generateSecret(spec)
                    .getEncoded();
            return aes(raw);
        } finally {
            spec.clearPassword();
        }
    }

    /** 返回 iv || ciphertext || tag。 */
    static byte[] seal(SecretKey key, byte[] plain, byte[] aad) throws GeneralSecurityException {
        byte[] iv = random(IV_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        if (aad != null && aad.length > 0) cipher.updateAAD(aad);
        byte[] ciphertext = cipher.doFinal(plain);
        byte[] packed = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
        return packed;
    }

    static byte[] open(SecretKey key, byte[] packed, byte[] aad) throws GeneralSecurityException {
        if (packed == null || packed.length <= IV_BYTES) {
            throw new GeneralSecurityException("密文长度异常");
        }
        byte[] iv = new byte[IV_BYTES];
        byte[] ciphertext = new byte[packed.length - IV_BYTES];
        System.arraycopy(packed, 0, iv, 0, IV_BYTES);
        System.arraycopy(packed, IV_BYTES, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        if (aad != null && aad.length > 0) cipher.updateAAD(aad);
        return cipher.doFinal(ciphertext);
    }
}
