package com.myvu.client.transport.ble;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** Shared name recognition and byte parsing for MYVU and IMIKI glasses. */
public final class GlassesAdvertisement {
    static final int IMIKI_NAME_MANUFACTURER_ID = 0x0bd2;
    private static final Pattern IMIKI_NAME = Pattern.compile(
            "IMIKI [0-9A-F]{4}", Pattern.CASE_INSENSITIVE);

    private GlassesAdvertisement() { }

    /**
     * Recognizes both advertised names and Android's cached bonded-device names.
     * Preserve the original MYVU matching, but require the observed four-digit
     * IMIKI identity suffix so unrelated products from that brand do not match.
     * Bonded glasses may stop advertising their manufacturer payload, so the
     * scan callback and bonded-device fallback must share this name predicate.
     */
    public static boolean matchesName(String name) {
        return name != null && (name.toUpperCase(Locale.US).contains("MYVU")
                || IMIKI_NAME.matcher(name).matches());
    }

    /**
     * Returns the name in the observed XGA010C manufacturer-data format, or null.
     * Android removes the two-byte manufacturer ID before returning this payload.
     * The observed prefix is 0x13, a one-byte length, then "IMIKI " and four hex
     * digits. This is deliberately not a general TLV decoder: trailing vendor
     * fields are opaque and are not needed to identify this glasses variant.
     */
    static String imikiName(int manufacturerId, byte[] data) {
        if (manufacturerId != IMIKI_NAME_MANUFACTURER_ID || data == null
                || data.length < 2 || (data[0] & 0xff) != 0x13) return null;
        int length = data[1] & 0xff;
        // Match the witnessed shape, not every device using this company ID.
        // Check the length before indexing or decoding untrusted radio data.
        if (length != 10 || length > data.length - 2) return null;
        byte[] prefix = { 'I', 'M', 'I', 'K', 'I', ' ' };
        for (int i = 0; i < prefix.length; i++) {
            if (data[i + 2] != prefix[i]) return null;
        }
        for (int i = 8; i < 12; i++) {
            int c = data[i] & 0xff;
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')
                    || (c >= 'a' && c <= 'f'))) return null;
        }
        return new String(data, 2, length, StandardCharsets.US_ASCII);
    }
}
