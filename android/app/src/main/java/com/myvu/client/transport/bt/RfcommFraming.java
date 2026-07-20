package com.myvu.client.transport.bt;

import com.myvu.client.protocol.Pb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Faithful port of myvu_client/myvu/rfcomm.py framing:
 *   eaca9353(magic) + len:4BE + 0002(const) + &lt;payload&gt;
 * Used uniformly for the ability/AUTH_SUCCESS session bring-up AND the later
 * relay/StMessage app frames.
 *
 * NOTE the length field is BIG-endian, unlike everything in the BLE packet
 * layer (transport/ble/BlePackets), which is little-endian.
 */
public final class RfcommFraming {
    private RfcommFraming() {}

    public static final byte[] MAGIC = { (byte) 0xea, (byte) 0xca, (byte) 0x93, (byte) 0x53 };
    public static final byte[] PREFIX = { 0x00, 0x02 };

    /**
     * The fixed channel the original test harness used. It answers the
     * ability/AUTH handshake but carries NO app traffic (zero ACKs) -- see
     * myvu_client/README.md. The real app relay is an RFCOMM service at a
     * per-session random UUID learned over BLE (CMD_SPP_SERVER_UUID_SYNC).
     *
     * Retained only so the pre-BLE harness flow keeps working; SppResolver
     * replaces it in Phase 4, at which point this constant goes away.
     */
    @Deprecated
    public static final int DEFAULT_CHANNEL = 13;

    public static byte[] encodeFrame(byte[] payload) {
        byte[] body = Pb.concat(PREFIX, payload);
        byte[] lenBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(body.length).array();
        return Pb.concat(MAGIC, lenBuf, body);
    }
}
