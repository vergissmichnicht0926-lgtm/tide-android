package com.whaleeye.tide;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;

/** 安装在 Keystore 中的 P-256 设备身份。 */
public final class DeviceIdentity {
    private static final String ALIAS = "tide.device.v1";
    private static final Object LOCK = new Object();

    private DeviceIdentity() {
    }

    public static String publicKeyBase64() throws Exception {
        KeyStore keyStore = loadStore();
        Certificate certificate = keyStore.getCertificate(ALIAS);
        if (certificate == null) throw new IllegalStateException("设备密钥不存在");
        return Base64.encodeToString(certificate.getPublicKey().getEncoded(), Base64.NO_WRAP);
    }

    public static String fingerprint() throws Exception {
        byte[] der = Base64.decode(publicKeyBase64(), Base64.NO_WRAP);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
        return hex.toString();
    }

    public static JSONObject proof(JSONObject challenge) throws Exception {
        String payload = challenge.optString("payload", "");
        if (payload.isEmpty()) throw new IllegalStateException("设备挑战缺少签名内容");
        KeyStore keyStore = loadStore();
        PrivateKey privateKey = (PrivateKey) keyStore.getKey(ALIAS, null);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(privateKey);
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.encodeToString(signer.sign(), Base64.NO_WRAP);
        JSONObject proof = new JSONObject();
        proof.put("device_public_key", publicKeyBase64());
        proof.put("key_fingerprint", fingerprint());
        proof.put("challenge_id", challenge.optString("challengeId",
                challenge.optString("challenge_id", "")));
        proof.put("signature", signature);
        return proof;
    }

    public static String deviceName() {
        String manufacturer = android.os.Build.MANUFACTURER == null
                ? "" : android.os.Build.MANUFACTURER.trim();
        String model = android.os.Build.MODEL == null ? "" : android.os.Build.MODEL.trim();
        String value = (manufacturer + " " + model).trim();
        return value.isEmpty() ? "Android" : value;
    }

    private static KeyStore loadStore() throws Exception {
        synchronized (LOCK) {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (!keyStore.containsAlias(ALIAS)) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
                KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                        ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build();
                generator.initialize(spec);
                generator.generateKeyPair();
            }
            return keyStore;
        }
    }
}
