package com.myvu.client.transport.ble;

import com.myvu.client.protocol.Pb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * BLE transport packet codec, ported from myvu_client/myvu/packets.py
 * (com.upuphone.starrynet.core.ble.channel.packet.*).
 *
 * EVERY multi-byte field here is LITTLE-endian. That is the opposite of the
 * TLV/relay layer and the eaca9353 frame length, which are big-endian, so each
 * ByteBuffer below sets the order explicitly rather than relying on the
 * default (Java's default is BIG_ENDIAN and would be silently wrong).
 *
 * Layout: 2-byte "sn" prefix.
 *   sn == 0 -> control packet: byte[2]=type, byte[3]=command/pkgType/status
 *   sn != 0 -> data fragment `sn`, payload = bytes[2:]
 */
public final class BlePackets {
    private BlePackets() {}

    // control types
    public static final int TYPE_CMD = 0;
    public static final int TYPE_ACK = 1;
    public static final int TYPE_SINGLE_CMD = 2;
    public static final int TYPE_SINGLE_ACK = 3;
    public static final int TYPE_MNG = 4;
    public static final int TYPE_MNG_ACK = 5;
    public static final int TYPE_FAST_CTR = 6;
    public static final int TYPE_FAST_ACK = 7;
    public static final int TYPE_MIX_CTR = 8;
    public static final int TYPE_SINGLE_CMD_NO_ACK = 9;

    // package types
    public static final int PKG_COMMON_DATA = 0;       // external/app channel
    public static final int PKG_STARRY_DATA = 16;      // pairing channel
    public static final int PKG_STARRY_DATA_INIT = 17; // first/negotiation message

    // ACK status
    public static final int ACK_SUCCESS = 0;
    public static final int ACK_READY = 1;
    public static final int ACK_BUSY = 2;
    public static final int ACK_TIMEOUT = 3;
    public static final int ACK_CANCEL = 4;
    public static final int ACK_SYNC = 5;

    // ------------------------------------------------------------ encoders

    private static ByteBuffer le(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** DataPacket.toBytes(): [seq:2][payload]. seq must be >= 1. */
    public static byte[] dataPacket(int seq, byte[] payload) {
        return Pb.concat(le(2).putShort((short) seq).array(), payload);
    }

    /** [00 00][type][pkgType][frameCount:2] */
    private static byte[] ctrLike(int type, int frameCount, int pkgType) {
        return le(6).putShort((short) 0)
                .put((byte) type)
                .put((byte) pkgType)
                .putShort((short) frameCount)
                .array();
    }

    public static byte[] ctrPacket(int frameCount, int pkgType) {
        return ctrLike(TYPE_CMD, frameCount, pkgType);
    }

    public static byte[] fastCtrPacket(int frameCount, int pkgType) {
        return ctrLike(TYPE_FAST_CTR, frameCount, pkgType);
    }

    public static byte[] mixCtrPacket(int frameCount, int pkgType, byte[] firstChunk) {
        return Pb.concat(ctrLike(TYPE_MIX_CTR, frameCount, pkgType), firstChunk);
    }

    /** [00 00][type][pkgType][payload] */
    private static byte[] singleLike(int type, int pkgType, byte[] payload) {
        byte[] head = le(4).putShort((short) 0).put((byte) type).put((byte) pkgType).array();
        return Pb.concat(head, payload);
    }

    public static byte[] singlePacket(int pkgType, byte[] payload) {
        return singleLike(TYPE_SINGLE_CMD, pkgType, payload);
    }

    public static byte[] singleNoAckPacket(int pkgType, byte[] payload) {
        return singleLike(TYPE_SINGLE_CMD_NO_ACK, pkgType, payload);
    }

    public static byte[] ackPacket(int status, List<Integer> lostSeqs) {
        byte[] out = le(4).putShort((short) 0).put((byte) TYPE_ACK).put((byte) status).array();
        if (lostSeqs != null && !lostSeqs.isEmpty()) {
            ByteBuffer bb = le(lostSeqs.size() * 2);
            for (Integer s : lostSeqs) bb.putShort((short) s.intValue());
            out = Pb.concat(out, bb.array());
        }
        return out;
    }

    public static byte[] ackPacket(int status) {
        return ackPacket(status, null);
    }

    public static byte[] fastAckPacket(int status) {
        return le(4).putShort((short) 0).put((byte) TYPE_FAST_ACK).put((byte) status).array();
    }

    public static byte[] singleAckPacket(int status) {
        return le(4).putShort((short) 0).put((byte) TYPE_SINGLE_ACK).put((byte) status).array();
    }

    // ------------------------------------------------------------- decoder

    /** Port of Packet.parse() + getPacket(). */
    public static BleParsedPacket parse(byte[] raw) {
        if (raw.length < 2) return new BleParsedPacket(0);

        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int sn = bb.getShort(0) & 0xFFFF;
        if (sn != 0) {
            // DataPacket: fragment `sn`, payload is the remainder.
            BleParsedPacket p = new BleParsedPacket(sn);
            p.value = Arrays.copyOfRange(raw, 2, raw.length);
            return p;
        }

        BleParsedPacket p = new BleParsedPacket(0);
        if (raw.length < 4) return p;
        p.type = raw[2] & 0xFF;
        p.command = raw[3] & 0xFF;

        if (p.type == TYPE_MIX_CTR) {
            if (raw.length >= 6) {
                p.params.add(bb.getShort(4) & 0xFFFF);
                p.value = Arrays.copyOfRange(raw, 6, raw.length);
            }
            return p;
        }

        int off = 4;
        while (off + 2 <= raw.length) {
            p.params.add(bb.getShort(off) & 0xFFFF);
            off += 2;
        }
        // SinglePacket / SingleNoAck carry raw payload from offset 4
        // (ByteUtils.get(value, 4) in the app).
        if (p.type == TYPE_SINGLE_CMD || p.type == TYPE_SINGLE_CMD_NO_ACK) {
            p.value = Arrays.copyOfRange(raw, 4, raw.length);
        }
        return p;
    }
}
