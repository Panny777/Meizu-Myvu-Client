package com.myvu.client.transport.ble;

import java.nio.charset.StandardCharsets;

/** Pure byte parsing for the manufacturer-only identity used by IMIKI glasses. */
final class GlassesAdvertisement {
    static final int IMIKI_NAME_MANUFACTURER_ID = 0x0bd2;

    private GlassesAdvertisement() { }

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
