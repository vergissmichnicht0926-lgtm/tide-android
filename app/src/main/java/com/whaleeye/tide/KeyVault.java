package com.whaleeye.tide;

import android.util.Base64;

import java.security.GeneralSecurityException;

import javax.crypto.SecretKey;

/**
 * 解锁后才装进内存的数据密钥。PIN 包裹和生物识别包裹都只是把这把 32 字节钥匙
 * 交给两条不同的门；门开了才允许 Session 读写密文。
 */
final class KeyVault {
    private static final String PREFIX = "v2:";
    private static volatile SecretKey key;

    private KeyVault() {
    }

    static boolean isReady() {
        return key != null;
    }

    static void install(byte[] raw) throws GeneralSecurityException {
        SecretKey next = CryptoBox.aes(raw);
        key = next;
    }

    static void clear() {
        key = null;
    }

    static byte[] copyKey() {
        SecretKey current = key;
        return current == null ? null : current.getEncoded();
    }

    static boolean isV2(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    static String encrypt(String plain) throws GeneralSecurityException {
        SecretKey current = key;
        if (current == null) throw new GeneralSecurityException("本地密钥尚未解锁");
        byte[] packed = CryptoBox.seal(current, plain.getBytes(java.nio.charset.Charset.forName("UTF-8")), null);
        return PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP);
    }

    static String decrypt(String value) throws GeneralSecurityException {
        SecretKey current = key;
        if (current == null) throw new GeneralSecurityException("本地密钥尚未解锁");
        if (!isV2(value)) throw new GeneralSecurityException("不是 v2 密文");
        byte[] packed = Base64.decode(value.substring(PREFIX.length()), Base64.NO_WRAP);
        byte[] plain = CryptoBox.open(current, packed, null);
        return new String(plain, java.nio.charset.Charset.forName("UTF-8"));
    }
}
