package com.myvu.client.protocol.link;

import com.myvu.client.protocol.Pb;
import com.myvu.client.protocol.PbValue;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * StarryNet LinkProtocol message builders/parsers, ported from
 * myvu_client/myvu/linkproto.py.
 *
 * These messages ride the BLE *internal* characteristic (0x2020) and carry the
 * ECDH bond plus the per-session SPP UUID sync.
 */
public final class LinkProtocol {
    private LinkProtocol() {}

    private static final byte[] EMPTY = new byte[0];

    // ------------------------------------------------------- LinkProtocol

    /** LinkProtocol{deviceId=dealDeviceId(identifier), cmd, data}. */
    public static byte[] build(byte[] identifier, int cmd, byte[] data) {
        byte[] out = Pb.bytes(1, DeviceId.deal(identifier));
        out = Pb.concat(out, Pb.varintField(2, cmd));
        if (data != null && data.length > 0) {
            out = Pb.concat(out, Pb.bytes(3, data));
        }
        return out;
    }

    public static byte[] build(byte[] identifier, int cmd) {
        return build(identifier, cmd, EMPTY);
    }

    public static LinkMessage parse(byte[] raw) {
        Map<Integer, List<PbValue>> f = Pb.parse(raw);
        return new LinkMessage(
                Pb.firstBytes(f, 1, EMPTY),
                (int) Pb.firstVarint(f, 2, 0),
                Pb.firstBytes(f, 3, EMPTY));
    }

    // -------------------------------------------------------- sub-messages

    /** WriteSwitchKey{1:key, 2:info}. */
    public static byte[] writeSwitchKey(byte[] key, byte[] info) {
        return Pb.concat(Pb.bytes(1, key), Pb.bytes(2, info));
    }

    /** Returns {key, info}. */
    public static byte[][] parseWriteSwitchKey(byte[] raw) {
        Map<Integer, List<PbValue>> f = Pb.parse(raw);
        return new byte[][] { Pb.firstBytes(f, 1, EMPTY), Pb.firstBytes(f, 2, EMPTY) };
    }

    /** WriteSwitchInfo{1:code, 2:info}; code is omitted when zero, as in Python. */
    public static byte[] writeSwitchInfo(byte[] info, int code) {
        byte[] out = EMPTY;
        if (code != 0) out = Pb.concat(out, Pb.varintField(1, code));
        return Pb.concat(out, Pb.bytes(2, info));
    }

    public static byte[] parseWriteSwitchInfo(byte[] raw) {
        return Pb.firstBytes(Pb.parse(raw), 2, EMPTY);
    }

    // ----------------------------------------------------------- SPP UUID

    /**
     * Decode a CMD_SPP_SERVER_UUID_SYNC payload into the full Bluetooth Base
     * UUID string, matching UUIDUtils.makeUUID(int) in the decompiled app.
     *
     * The 4-byte payload is LITTLE-endian -- confirmed empirically: a captured
     * payload of 21 91 00 00 only lands inside ByteUtils' expected range
     * (SecureRandom.nextInt(65535)) when read little-endian (0x9121 = 37153);
     * big-endian would give 0x21910000, far out of range.
     *
     * This is the opposite endianness to the TLV layer, so do not "unify" them.
     */
    public static String sppShortUuidToString(byte[] data) {
        if (data == null || data.length < 4) {
            throw new IllegalArgumentException("SPP UUID payload must be 4 bytes, got "
                    + (data == null ? 0 : data.length));
        }
        int shortUuid = (data[0] & 0xFF)
                | ((data[1] & 0xFF) << 8)
                | ((data[2] & 0xFF) << 16)
                | ((data[3] & 0xFF) << 24);
        return String.format(Locale.US, "0000%04x-0000-1000-8000-00805f9b34fb", shortUuid);
    }
}
