package com.myvu.client;

import com.myvu.client.core.Hex;

/**
 * Real captured handshake bytes, taken verbatim from
 * btcsnoop_hci_full_session.log (handle 0x0023, the internal/link channel).
 * Same constants as myvu_client/selftest.py, so the Java and Python codecs are
 * validated against identical ground truth.
 */
public final class CapturedFrames {
    private CapturedFrames() {}

    /** phone: FAST_CTR init */
    public static final byte[] F479 = Hex.decode("000006110100");

    /** phone: version negotiation JSON */
    public static final byte[] F480 = Hex.decode(
            "01007b2269223a22376361333735643039346631222c2276223a332c2265223a"
          + "352c226d223a3531322c2262223a322c2263223a2239393939227d");

    /** glasses: version reply JSON (negotiates e=1 -> AES/CBC) */
    public static final byte[] F483 = Hex.decode(
            "000009117b2269223a22324336463445303044433437222c2276223a342c2265"
          + "223a312c226d223a3531322c2262223a327d");

    /** phone: WRITE_SWITCH_KEY carrying the P-256 SPKI public key */
    public static final byte[] F484 = Hex.decode(
            "000002100a060e6b2f8a5c83100b1a650a5b3059301306072a8648ce3d020106"
          + "082a8648ce3d0301070342000440620bda2512a57f5716887ed299beea0f02c3"
          + "9675cd831d64ceb27dab9ae52eaea1b6c4dc7999906767a68ffe3b6d9eb95244"
          + "48053341f62f7e9ede5458a2b812067ca375d094f1");

    /** The phone's MAC in this capture. */
    public static final byte[] PHONE_MAC = Hex.decode("7ca375d094f1");

    /** dealDeviceId(PHONE_MAC), as it appears on the wire. */
    public static final byte[] PHONE_DEVICE_ID = Hex.decode("0e6b2f8a5c83");
}
