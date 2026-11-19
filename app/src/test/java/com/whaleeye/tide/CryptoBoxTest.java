package com.whaleeye.tide;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.SecretKey;

public final class CryptoBoxTest {
    @Test
    public void sealOpenRoundTrip() throws Exception {
        SecretKey key = CryptoBox.aes(CryptoBox.random(CryptoBox.KEY_BYTES));
        byte[] plain = "tide-session-token".getBytes(StandardCharsets.UTF_8);
        byte[] packed = CryptoBox.seal(key, plain, "aad".getBytes(StandardCharsets.UTF_8));
        assertTrue(packed.length > CryptoBox.IV_BYTES);
        assertArrayEquals(plain, CryptoBox.open(key, packed, "aad".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void tamperedCiphertextFails() throws Exception {
        SecretKey key = CryptoBox.aes(CryptoBox.random(CryptoBox.KEY_BYTES));
        byte[] packed = CryptoBox.seal(key, new byte[]{1, 2, 3}, null);
        packed[packed.length - 1] ^= 0x40;
        try {
            CryptoBox.open(key, packed, null);
            fail("tampered ciphertext must not open");
        } catch (GeneralSecurityException expected) {
            assertTrue(expected instanceof javax.crypto.AEADBadTagException);
        }
    }

    @Test
    public void wrongAadFails() throws Exception {
        SecretKey key = CryptoBox.aes(CryptoBox.random(CryptoBox.KEY_BYTES));
        byte[] packed = CryptoBox.seal(key, new byte[]{9, 9}, "pin".getBytes(StandardCharsets.UTF_8));
        try {
            CryptoBox.open(key, packed, "bio".getBytes(StandardCharsets.UTF_8));
            fail("AAD mismatch must not open");
        } catch (GeneralSecurityException expected) {
            assertTrue(expected instanceof javax.crypto.AEADBadTagException);
        }
    }

    @Test
    public void wrongKeyFails() throws Exception {
        SecretKey key = CryptoBox.aes(CryptoBox.random(CryptoBox.KEY_BYTES));
        SecretKey other = CryptoBox.aes(CryptoBox.random(CryptoBox.KEY_BYTES));
        byte[] packed = CryptoBox.seal(key, new byte[]{4, 5, 6}, null);
        try {
            CryptoBox.open(other, packed, null);
            fail("wrong key must not open");
        } catch (GeneralSecurityException expected) {
            assertTrue(expected instanceof javax.crypto.AEADBadTagException);
        }
    }

    @Test
    public void pinKekIsDeterministicAndSaltSeparated() throws Exception {
        byte[] salt = CryptoBox.random(16);
        byte[] otherSalt = CryptoBox.random(16);
        SecretKey a = CryptoBox.pinKek("123456".toCharArray(), salt);
        SecretKey b = CryptoBox.pinKek("123456".toCharArray(), salt);
        SecretKey c = CryptoBox.pinKek("123456".toCharArray(), otherSalt);
        assertArrayEquals(a.getEncoded(), b.getEncoded());
        assertFalse(Arrays.equals(a.getEncoded(), c.getEncoded()));
        assertEquals(CryptoBox.KEY_BYTES, a.getEncoded().length);
    }

    @Test
    public void randomLengthMatches() {
        assertEquals(32, CryptoBox.random(32).length);
        assertEquals(12, CryptoBox.random(12).length);
    }
}
