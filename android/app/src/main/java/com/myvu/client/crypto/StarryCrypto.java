package com.myvu.client.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Symmetric layer, ported from myvu_client/myvu/crypto.py.
 *
 * The mode is NEGOTIATED at runtime via the "e" field of the version JSON --
 * it is not a fixed constant. A captured live session negotiated e=1 (CBC),
 * so that is the best-tested path.
 */
public final class StarryCrypto {
    private StarryCrypto() {}

    public static final int SYMMETRIC_V1_CBC = 1;
    public static final int SYMMETRIC_V2_CTR = 2;
    public static final int SYMMETRIC_V3_GCM = 3; // and any other value

    /**
     * EncryptionUtil.generateIV(): the first 16 characters of a dash-stripped
     * UUID4, taken as ASCII bytes. Note this is 16 ASCII *characters*, not 16
     * bytes of entropy -- each byte is a hex digit, so the real entropy is 64
     * bits. That is what the device expects; do not "improve" it.
     */
    public static byte[] generateIv() {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return hex.substring(0, 16).getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] encrypt(byte[] plaintext, byte[] key, byte[] iv, int mode)
            throws GeneralSecurityException {
        return cipher(Cipher.ENCRYPT_MODE, plaintext, key, iv, mode);
    }

    public static byte[] decrypt(byte[] ciphertext, byte[] key, byte[] iv, int mode)
            throws GeneralSecurityException {
        return cipher(Cipher.DECRYPT_MODE, ciphertext, key, iv, mode);
    }

    private static byte[] cipher(int opmode, byte[] input, byte[] key, byte[] iv, int mode)
            throws GeneralSecurityException {
        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        Cipher c;
        switch (mode) {
            case SYMMETRIC_V1_CBC:
                c = Cipher.getInstance("AES/CBC/PKCS5Padding");
                c.init(opmode, keySpec, new IvParameterSpec(iv));
                break;
            case SYMMETRIC_V2_CTR:
                c = Cipher.getInstance("AES/CTR/NoPadding");
                c.init(opmode, keySpec, new IvParameterSpec(iv));
                break;
            default:
                // Java's AES/GCM appends the 128-bit tag to the ciphertext,
                // which is what the Python AESGCM helper does too.
                c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(opmode, keySpec, new GCMParameterSpec(128, iv));
                break;
        }
        return c.doFinal(input);
    }
}
