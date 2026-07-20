package com.myvu.client.protocol.link;

import java.util.Locale;

/** BleUtil.dealDeviceId and the MAC-string helpers around it. */
public final class DeviceId {
    private DeviceId() {}

    /**
     * BleUtil.dealDeviceId: reverse the byte order AND bitwise-NOT each byte.
     * Verified against a real capture: dealDeviceId(7ca375d094f1) == 0e6b2f8a5c83.
     */
    public static byte[] deal(byte[] identifier) {
        byte[] out = new byte[identifier.length];
        for (int i = 0; i < identifier.length; i++) {
            out[i] = (byte) (~identifier[identifier.length - 1 - i] & 0xFF);
        }
        return out;
    }

    /** Utils.getBytesFromAddress("AA:BB:.."), tolerant of case and separators. */
    public static byte[] macToBytes(String mac) {
        String clean = mac.replace(":", "").replace("-", "");
        if (clean.length() != 12) {
            throw new IllegalArgumentException("not a 6-byte MAC: " + mac);
        }
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    /** Lowercase, separator-free MAC -- the form used as deviceId in the auth bean. */
    public static String macToHex(String mac) {
        return mac.replace(":", "").replace("-", "").toLowerCase(Locale.US);
    }
}
