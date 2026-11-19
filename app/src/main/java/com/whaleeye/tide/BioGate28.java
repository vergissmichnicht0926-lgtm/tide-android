package com.whaleeye.tide;

import android.app.Activity;
import android.content.Context;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 只在 API 28+ 加载；这里可以直接引用 BiometricPrompt。 */
final class BioGate28 {
    private static final String KEY_ALIAS = "tide.bio.v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final byte[] AAD = "tide-bio-v1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final int BIOMETRIC_STRONG = 0x000F;

    private static volatile CancellationSignal active;

    private BioGate28() {
    }

    static boolean isSupported(Context context) {
        // Android 16 上 BiometricManager.from(Context) 已不可反射；先取系统服务。
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Object manager = context.getSystemService("biometric");
                if (manager != null) {
                    int result;
                    if (Build.VERSION.SDK_INT >= 30) {
                        result = (Integer) manager.getClass()
                                .getMethod("canAuthenticate", int.class)
                                .invoke(manager, BIOMETRIC_STRONG);
                    } else {
                        result = (Integer) manager.getClass()
                                .getMethod("canAuthenticate")
                                .invoke(manager);
                    }
                    android.util.Log.d("BioGate", "service canAuthenticate(strong)=" + result);
                    if (result == 0) return true;
                    // 锁定期可能返回 HW_UNAVAILABLE；继续用指纹服务判断硬件/录入状态。
                    android.util.Log.d("BioGate", "canAuthenticate not ready, fallback");
                }
            } catch (Exception serviceError) {
                android.util.Log.w("BioGate", "biometric service check failed", serviceError);
            }
        }
        // 老版本/服务不可用时退回指纹服务；弱人脸机型会走 PIN。
        try {
            FingerprintManager fm = context.getSystemService(FingerprintManager.class);
            boolean ok = fm != null && fm.isHardwareDetected() && fm.hasEnrolledFingerprints();
            android.util.Log.d("BioGate", "fingerprint fallback supported=" + ok);
            return ok;
        } catch (Exception fallbackError) {
            android.util.Log.w("BioGate", "fingerprint fallback failed", fallbackError);
            return false;
        }
    }

    static void authenticate(Activity activity, BioGate.Callback callback) {
        if (!LockManager.hasBioWrap(activity)) {
            callback.onPermanentFailure("还没有绑定生物识别，请先用 PIN 解锁");
            return;
        }
        SecretKey key = loadKey();
        if (key == null) {
            LockManager.invalidateBio(activity, "生物识别密钥不存在，请用 PIN 解锁后重新绑定");
            callback.onPermanentFailure("生物识别密钥不存在，请用 PIN 解锁后重新绑定");
            return;
        }
        byte[] packed = LockManager.bioWrap(activity);
        if (packed == null || packed.length <= CryptoBox.IV_BYTES) {
            LockManager.invalidateBio(activity, "生物识别包裹数据损坏，请用 PIN 解锁后重新绑定");
            callback.onPermanentFailure("生物识别包裹数据损坏，请用 PIN 解锁后重新绑定");
            return;
        }
        byte[] iv = Arrays.copyOfRange(packed, 0, CryptoBox.IV_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(packed, CryptoBox.IV_BYTES, packed.length);
        Cipher cipher;
        try {
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(CryptoBox.TAG_BITS, iv));
        } catch (KeyPermanentlyInvalidatedException e) {
            LockManager.invalidateBio(activity, "指纹或人脸信息已变更，请用 PIN 解锁后重新绑定");
            callback.onPermanentFailure("指纹或人脸信息已变更，请用 PIN 解锁后重新绑定");
            return;
        } catch (UserNotAuthenticatedException e) {
            callback.onFailure("生物识别暂时不可用，请使用 PIN");
            return;
        } catch (Exception e) {
            LockManager.invalidateBio(activity, "生物识别密钥不可用：" + shortMessage(e));
            callback.onPermanentFailure("生物识别密钥不可用，请用 PIN 解锁后重新绑定");
            return;
        }

        CancellationSignal signal = new CancellationSignal();
        active = signal;
        BiometricPrompt prompt;
        try {
            BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                    .setTitle("解锁汐语")
                    .setSubtitle("使用指纹或人脸")
                    .setNegativeButton("使用 PIN", activity.getMainExecutor(),
                            (dialog, which) -> {
                                active = null;
                                callback.onCancelled();
                            });
            if (Build.VERSION.SDK_INT >= 29) builder.setConfirmationRequired(false);
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setAllowedAuthenticators(BIOMETRIC_STRONG);
            }
            prompt = builder.build();
        } catch (Exception e) {
            active = null;
            callback.onFailure("无法启动生物识别：" + shortMessage(e));
            return;
        }
        prompt.authenticate(new BiometricPrompt.CryptoObject(cipher), signal,
                activity.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        active = null;
                        try {
                            Cipher authorized = result.getCryptoObject() == null
                                    ? null : result.getCryptoObject().getCipher();
                            if (authorized == null) throw new GeneralSecurityException("CryptoObject 为空");
                            authorized.updateAAD(AAD);
                            byte[] dek = authorized.doFinal(ciphertext);
                            LockManager.unlockWithDataKey(activity, dek);
                            callback.onSuccess();
                        } catch (AEADBadTagException e) {
                            LockManager.invalidateBio(activity,
                                    "生物识别包裹与本地数据不匹配，请用 PIN 解锁后重新绑定");
                            callback.onPermanentFailure("生物识别包裹已损坏，请用 PIN 解锁后重新绑定");
                        } catch (UserNotAuthenticatedException e) {
                            callback.onFailure("生物识别授权已过期，请再试一次或使用 PIN");
                        } catch (GeneralSecurityException e) {
                            LockManager.invalidateBio(activity,
                                    "生物识别解出的数据密钥不可用，请用 PIN 解锁后重新绑定");
                            callback.onPermanentFailure("生物识别数据密钥不可用，请用 PIN 解锁后重新绑定");
                        } catch (RuntimeException e) {
                            callback.onFailure("生物识别解密失败：" + shortMessage(e));
                        }
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence message) {
                        active = null;
                        String text = message == null ? "生物识别失败" : message.toString();
                        if (code == 5 || code == 10 || code == 13) {
                            callback.onCancelled();
                        } else {
                            callback.onFailure(text + "，请使用 PIN");
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // 系统会继续留在提示框里；这不是取消，也不消耗 PIN 次数。
                    }
                });
    }

    static void bind(Activity activity, BioGate.Callback callback) {
        byte[] dek = LockManager.copyDataKey();
        if (dek == null) {
            callback.onFailure("应用尚未解锁，无法绑定生物识别");
            return;
        }
        SecretKey key;
        Cipher cipher;
        try {
            deleteKey();
            key = createKey();
            cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
        } catch (Exception e) {
            LockManager.recordBioFailure(activity, "无法创建 auth-bound key：" + shortMessage(e));
            callback.onPermanentFailure("无法创建生物识别密钥：" + shortMessage(e));
            return;
        }
        CancellationSignal signal = new CancellationSignal();
        active = signal;
        BiometricPrompt prompt;
        try {
            BiometricPrompt.Builder builder = new BiometricPrompt.Builder(activity)
                    .setTitle("启用生物识别")
                    .setSubtitle("验证一次，之后可用指纹或人脸解锁")
                    .setNegativeButton("暂不启用", activity.getMainExecutor(),
                            (dialog, which) -> {
                                active = null;
                                LockManager.disableBio(activity);
                                callback.onCancelled();
                            });
            if (Build.VERSION.SDK_INT >= 29) builder.setConfirmationRequired(false);
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setAllowedAuthenticators(BIOMETRIC_STRONG);
            }
            prompt = builder.build();
        } catch (Exception e) {
            active = null;
            deleteKey();
            LockManager.recordBioFailure(activity, "无法启动生物识别：" + shortMessage(e));
            callback.onFailure("无法启动生物识别：" + shortMessage(e));
            return;
        }
        prompt.authenticate(new BiometricPrompt.CryptoObject(cipher), signal,
                activity.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        active = null;
                        try {
                            Cipher authorized = result.getCryptoObject() == null
                                    ? null : result.getCryptoObject().getCipher();
                            if (authorized == null) throw new GeneralSecurityException("CryptoObject 为空");
                            authorized.updateAAD(AAD);
                            byte[] iv = authorized.getIV();
                            byte[] ct = authorized.doFinal(dek);
                            if (iv == null || iv.length != CryptoBox.IV_BYTES) {
                                throw new GeneralSecurityException("IV 长度异常");
                            }
                            byte[] packed = new byte[iv.length + ct.length];
                            System.arraycopy(iv, 0, packed, 0, iv.length);
                            System.arraycopy(ct, 0, packed, iv.length, ct.length);
                            LockManager.saveBioWrap(activity, packed);
                            callback.onSuccess();
                        } catch (Exception e) {
                            LockManager.recordBioFailure(activity,
                                    "绑定生物识别失败：" + shortMessage(e));
                            callback.onPermanentFailure("绑定生物识别失败：" + shortMessage(e));
                        }
                    }

                    @Override
                    public void onAuthenticationError(int code, CharSequence message) {
                        active = null;
                        LockManager.disableBio(activity);
                        if (code == 5 || code == 10 || code == 13) {
                            callback.onCancelled();
                        } else {
                            callback.onFailure(message == null ? "生物识别失败" : message.toString());
                        }
                    }

                    @Override
                    public void onAuthenticationFailed() {
                    }
                });
    }

    static void cancel() {
        CancellationSignal signal = active;
        active = null;
        if (signal != null) {
            try {
                signal.cancel();
            } catch (RuntimeException ignored) {
            }
        }
    }

    static void deleteKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
        }
    }

    private static SecretKey loadKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            java.security.Key key = keyStore.getKey(KEY_ALIAS, null);
            return key instanceof SecretKey ? (SecretKey) key : null;
        } catch (UnrecoverableKeyException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static SecretKey createKey() throws GeneralSecurityException {
        try {
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(true);
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
            } else {
                builder.setUserAuthenticationValidityDurationSeconds(-1);
            }
            builder.setInvalidatedByBiometricEnrollment(true);
            generator.init(builder.build());
            return generator.generateKey();
        } catch (GeneralSecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new GeneralSecurityException(e);
        }
    }

    private static String shortMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
