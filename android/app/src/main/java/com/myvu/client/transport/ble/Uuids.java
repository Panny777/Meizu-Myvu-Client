package com.myvu.client.transport.ble;

import java.util.Locale;
import java.util.UUID;

/**
 * GATT UUIDs for the MYVU / StarryNet BLE protocol, from
 * com.upuphone.starrynet.core.ble.BluetoothConstants and UUIDUtils.
 */
public final class Uuids {
    private Uuids() {}

    /** UUIDUtils.makeUUID(i) -> "0000{i:04x}-0000-1000-8000-00805f9b34fb". */
    public static UUID make(int i) {
        return UUID.fromString(String.format(Locale.US,
                "0000%04x-0000-1000-8000-00805f9b34fb", i));
    }

    /** STARRY_NET_SERVICE_UUID = makeUUID(3025) -> 00000bd1-... */
    public static final UUID SERVICE = make(3025);

    // "Air" glasses characteristics -- the pair seen in the btsnoop capture
    // (handles 0x0023 / 0x0026).
    /** Link/pairing channel: version negotiation + the ECDH handshake. */
    public static final UUID AIR_INTERNAL = make(0x2020);
    /** Application data channel: JSON {"action": ...} messages. */
    public static final UUID AIR_EXTERNAL = make(0x2021);
    /** High-priority external messages -- also carries the 3s heartbeat. */
    public static final UUID AIR_URGENT = make(0x2022);
    public static final UUID GLASS_WRITE = make(0x2023);

    // "V2" characteristics -- other device generations advertise these instead.
    public static final UUID V2_INTERNAL = make(0x2010);
    public static final UUID V2_EXTERNAL = make(0x2011);
    public static final UUID V2_URGENT = make(0x2012);

    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /**
     * Preferred (internal, external, urgent) triples in priority order. The
     * client probes the connected device and takes the first whose internal and
     * external characteristics are both present; the urgent one is optional.
     */
    public static final UUID[][] CHANNEL_SETS = {
            { AIR_INTERNAL, AIR_EXTERNAL, AIR_URGENT },
            { V2_INTERNAL, V2_EXTERNAL, V2_URGENT },
    };
}
