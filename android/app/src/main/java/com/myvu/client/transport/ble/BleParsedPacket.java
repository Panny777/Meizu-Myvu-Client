package com.myvu.client.transport.ble;

import java.util.ArrayList;
import java.util.List;

/** One decoded BLE transport packet (packets.ParsedPacket). */
public class BleParsedPacket {
    public final int sn;
    /** Control-packet type; -1 for data fragments. */
    public int type = -1;
    /** byte[3]: package type for CTR/SINGLE, or ACK status for the ACK types. */
    public int command = -1;
    /** Trailing little-endian shorts (frameCount, lost sequence numbers). */
    public final List<Integer> params = new ArrayList<>();
    public byte[] value = new byte[0];

    public BleParsedPacket(int sn) {
        this.sn = sn;
    }

    public boolean isData() { return sn != 0; }

    public int pkgType() { return command; }

    public int frameCount() { return params.isEmpty() ? 0 : params.get(0); }

    /** For ACK/FAST_ACK/SINGLE_ACK the "command" byte carries the status. */
    public int ackStatus() { return command; }
}
