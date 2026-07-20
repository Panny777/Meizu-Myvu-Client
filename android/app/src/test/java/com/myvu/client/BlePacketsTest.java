package com.myvu.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.myvu.client.protocol.link.DeviceId;
import com.myvu.client.protocol.link.LinkCommands;
import com.myvu.client.protocol.link.LinkMessage;
import com.myvu.client.protocol.link.LinkProtocol;
import com.myvu.client.transport.ble.BlePackets;
import com.myvu.client.transport.ble.BleParsedPacket;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Mirrors myvu_client/selftest.py checks 1-7: the BLE packet codec, the
 * LinkProtocol protobuf, and dealDeviceId, all validated against real captured
 * bytes.
 */
public class BlePacketsTest {

    @Test
    public void dealDeviceIdMatchesTheWire() {
        assertArrayEquals(CapturedFrames.PHONE_DEVICE_ID, DeviceId.deal(CapturedFrames.PHONE_MAC));
    }

    @Test
    public void dealDeviceIdIsItsOwnInverse() {
        // reverse + bitwise-NOT is an involution, which is why the same helper
        // works in both directions.
        assertArrayEquals(CapturedFrames.PHONE_MAC,
                DeviceId.deal(DeviceId.deal(CapturedFrames.PHONE_MAC)));
    }

    @Test
    public void fastCtrNegotiationPacketParses() {
        BleParsedPacket p = BlePackets.parse(CapturedFrames.F479);
        assertEquals(BlePackets.TYPE_FAST_CTR, p.type);
        assertEquals(BlePackets.PKG_STARRY_DATA_INIT, p.pkgType());
        assertEquals(1, p.frameCount());
        assertFalse(p.isData());
    }

    @Test
    public void versionJsonReassemblesFromDataFragment() throws Exception {
        BleParsedPacket p = BlePackets.parse(CapturedFrames.F480);
        assertTrue(p.isData());
        assertEquals(1, p.sn);

        JSONObject own = new JSONObject(new String(p.value, StandardCharsets.UTF_8));
        assertEquals("7ca375d094f1", own.getString("i"));
        assertEquals(5, own.getInt("e"));
        assertEquals(3, own.getInt("v"));
    }

    @Test
    public void glassesReplyIsSingleNoAckAndNegotiatesCbc() throws Exception {
        BleParsedPacket p = BlePackets.parse(CapturedFrames.F483);
        assertEquals(BlePackets.TYPE_SINGLE_CMD_NO_ACK, p.type);

        JSONObject peer = new JSONObject(new String(p.value, StandardCharsets.UTF_8));
        // e=1 -> AES/CBC. This is the mode a real session actually used, which
        // is why CryptoTest exercises CBC hardest.
        assertEquals(1, peer.getInt("e"));
    }

    @Test
    public void writeSwitchKeyFrameDecodesFully() {
        BleParsedPacket p = BlePackets.parse(CapturedFrames.F484);
        assertEquals(BlePackets.TYPE_SINGLE_CMD, p.type);
        assertEquals(BlePackets.PKG_STARRY_DATA, p.pkgType());

        LinkMessage lp = LinkProtocol.parse(p.value);
        assertArrayEquals(CapturedFrames.PHONE_DEVICE_ID, lp.deviceId);
        assertEquals(LinkCommands.CMD_WRITE_SWITCH_KEY, lp.cmd);

        byte[][] keyInfo = LinkProtocol.parseWriteSwitchKey(lp.data);
        byte[] key = keyInfo[0];
        byte[] info = keyInfo[1];
        assertArrayEquals(CapturedFrames.PHONE_MAC, info);
        assertEquals("P-256 SPKI DER is 91 bytes", 91, key.length);
        assertEquals((byte) 0x30, key[0]);
        assertEquals((byte) 0x59, key[1]);
    }

    /**
     * The highest-value test in the suite: re-encoding the captured
     * WRITE_SWITCH_KEY must reproduce it byte for byte. One assertion validates
     * the protobuf writer, dealDeviceId, and little-endian packet framing
     * simultaneously -- if the BLE layer's endianness is wrong anywhere, this
     * fails immediately.
     */
    @Test
    public void reEncodingReproducesCapturedFrameExactly() {
        BleParsedPacket p = BlePackets.parse(CapturedFrames.F484);
        byte[][] keyInfo = LinkProtocol.parseWriteSwitchKey(LinkProtocol.parse(p.value).data);

        byte[] rebuilt = BlePackets.singlePacket(
                BlePackets.PKG_STARRY_DATA,
                LinkProtocol.build(
                        CapturedFrames.PHONE_MAC,
                        LinkCommands.CMD_WRITE_SWITCH_KEY,
                        LinkProtocol.writeSwitchKey(keyInfo[0], CapturedFrames.PHONE_MAC)));

        assertArrayEquals(CapturedFrames.F484, rebuilt);
    }

    @Test
    public void controlPacketEncodersAreLittleEndian() {
        // frameCount 1 must land as 01 00, not 00 01. Java's ByteBuffer
        // defaults to BIG_ENDIAN, so this is the guard against forgetting
        // .order(LITTLE_ENDIAN) somewhere in BlePackets.
        assertArrayEquals(CapturedFrames.F479,
                BlePackets.fastCtrPacket(1, BlePackets.PKG_STARRY_DATA_INIT));

        byte[] ctr = BlePackets.ctrPacket(258, BlePackets.PKG_COMMON_DATA);
        assertEquals((byte) 0x02, ctr[4]);
        assertEquals((byte) 0x01, ctr[5]);
    }

    @Test
    public void dataPacketRoundTrips() {
        byte[] payload = "hello glasses".getBytes(StandardCharsets.UTF_8);
        BleParsedPacket p = BlePackets.parse(BlePackets.dataPacket(7, payload));
        assertTrue(p.isData());
        assertEquals(7, p.sn);
        assertArrayEquals(payload, p.value);
    }

    @Test
    public void mixCtrCarriesFrameCountAndFirstChunk() {
        byte[] chunk = { 1, 2, 3, 4 };
        BleParsedPacket p = BlePackets.parse(
                BlePackets.mixCtrPacket(3, BlePackets.PKG_COMMON_DATA, chunk));
        assertEquals(BlePackets.TYPE_MIX_CTR, p.type);
        assertEquals(3, p.frameCount());
        assertArrayEquals(chunk, p.value);
    }

    @Test
    public void ackStatusDecodes() {
        BleParsedPacket p = BlePackets.parse(BlePackets.singleAckPacket(BlePackets.ACK_SUCCESS));
        assertEquals(BlePackets.TYPE_SINGLE_ACK, p.type);
        assertEquals(BlePackets.ACK_SUCCESS, p.ackStatus());
    }

    @Test
    public void shortAndEmptyBuffersDoNotCrash() {
        // Inbound data comes off a radio; truncated reads must not throw.
        assertEquals(0, BlePackets.parse(new byte[0]).sn);
        assertEquals(0, BlePackets.parse(new byte[] { 0 }).sn);
        assertEquals(-1, BlePackets.parse(new byte[] { 0, 0 }).type);
    }
}
