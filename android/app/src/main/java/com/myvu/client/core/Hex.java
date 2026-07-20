package com.myvu.client.core;

/** Hex encode/decode. Pure Java so it is usable from JVM unit tests. */
public final class Hex {
    private Hex() {}

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    public static String encode(byte[] data) {
        return encode(data, 0, data.length);
    }

    public static String encode(byte[] data, int off, int len) {
        char[] out = new char[len * 2];
        for (int i = 0; i < len; i++) {
            int b = data[off + i] & 0xFF;
            out[i * 2] = DIGITS[b >>> 4];
            out[i * 2 + 1] = DIGITS[b & 0x0F];
        }
        return new String(out);
    }

    /** Decodes a hex string, ignoring whitespace. */
    public static byte[] decode(String hex) {
        StringBuilder clean = new StringBuilder(hex.length());
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (!Character.isWhitespace(c)) clean.append(c);
        }
        if (clean.length() % 2 != 0) {
            throw new IllegalArgumentException("hex string has odd length: " + clean.length());
        }
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("bad hex at offset " + (i * 2));
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
