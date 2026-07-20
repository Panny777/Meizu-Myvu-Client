package com.myvu.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.myvu.client.protocol.MsgType;
import com.myvu.client.protocol.Relay;
import com.myvu.client.protocol.RelayMessage;
import com.myvu.client.protocol.RelaySequencer;
import com.myvu.client.protocol.TlvBox;
import com.myvu.client.protocol.TlvTags;
import com.myvu.client.transport.bt.FrameReassembler;
import com.myvu.client.transport.bt.RfcommFraming;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** TLV codec, relay framing, sequencing, and the eaca9353 stream framing. */
public class TlvRelayTest {

    @Test
    public void tlvIntsAreBigEndian() {
        // Opposite endianness to the BLE packet layer -- the single easiest
        // thing to get wrong when porting, so assert the actual bytes.
        byte[] ser = new TlvBox().putInt(TlvTags.MSG_ID, 1).serialize();
        // [tag][len:2][value:4]
        assertEquals((byte) TlvTags.MSG_ID, ser[0]);
        assertEquals(0, ser[1]);
        assertEquals(4, ser[2]);
        assertArrayEquals(new byte[] { 0, 0, 0, 1 }, Arrays.copyOfRange(ser, 3, 7));
    }

    @Test
    public void tlvPreservesInsertionOrder() {
        // TlvBox must be backed by a LinkedHashMap: the glasses accept our
        // frames, but byte-identical output vs the capture is how we verify
        // them, and a HashMap would reorder tags.
        byte[] ser = new TlvBox()
                .putByte(100, 3)
                .putInt(101, 7)
                .putByte(103, 1)
                .serialize();
        assertEquals(100, ser[0] & 0xFF);
        assertEquals(101, ser[4] & 0xFF);
        assertEquals(103, ser[11] & 0xFF);
    }

    @Test
    public void tlvRoundTrips() {
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        TlvBox parsed = TlvBox.parse(new TlvBox()
                .putByte(TlvTags.MSG_TYPE, MsgType.SEND)
                .putInt(TlvTags.MSG_ID, 42)
                .putBytes(TlvTags.MSG_BODY, body)
                .serialize());

        assertEquals(Integer.valueOf(MsgType.SEND), parsed.getByte(TlvTags.MSG_TYPE));
        assertEquals(Integer.valueOf(42), parsed.getInt(TlvTags.MSG_ID));
        assertArrayEquals(body, parsed.getBytes(TlvTags.MSG_BODY));
        assertNull("absent tags return null so callers can default",
                parsed.getInt(TlvTags.ERROR_CODE));
    }

    @Test
    public void relayFrameRoundTrips() {
        byte[] body = "{\"action\":\"notification\"}".getBytes(StandardCharsets.UTF_8);
        byte[] frame = Relay.buildFrame(Relay.DEFAULT_CATEGORY, MsgType.SEND, 1, 1, 1, body);

        RelayMessage m = Relay.parseFrame(frame);
        assertNotNull(m);
        assertEquals(Relay.DEFAULT_CATEGORY, m.category);
        assertEquals(MsgType.SEND, m.msgType);
        assertEquals(1, m.msgId);
        assertEquals(1, m.needCallback);
        assertArrayEquals(body, m.msgBody);
    }

    @Test
    public void nonRelayBuffersParseToNull() {
        assertNull(Relay.parseFrame(new byte[0]));
        assertNull(Relay.parseFrame(new byte[] { 0x02, 0x00 })); // wrong frame prefix
    }

    @Test
    public void sequencerStartsAtOne() {
        // Load-bearing: the glasses track the last received sequence number
        // (0 on a fresh connect) and discard anything that looks out of order,
        // so every new connection must restart the sequence at 1.
        RelaySequencer seq = new RelaySequencer();
        assertEquals(0, seq.getOutId());

        RelayMessage first = Relay.parseFrame(seq.dataFrame(new byte[] { 1 }));
        assertNotNull(first);
        assertEquals(1, first.msgId);

        RelayMessage second = Relay.parseFrame(seq.dataFrame(new byte[] { 2 }));
        assertNotNull(second);
        assertEquals(2, second.msgId);
    }

    @Test
    public void ackFrameEchoesPeerIdAndCategory() {
        RelayMessage inbound = Relay.parseFrame(
                Relay.buildFrame(5, MsgType.SEND, 99, 1, 1, new byte[] { 7 }));
        assertNotNull(inbound);

        RelayMessage ack = Relay.parseFrame(new RelaySequencer().ackFrame(inbound));
        assertNotNull(ack);
        assertEquals(MsgType.SEND_SUCCESS, ack.msgType);
        assertEquals(99, ack.msgId);
        assertEquals("ACK must be sent in the peer's category", 5, ack.category);
    }

    @Test
    public void rfcommFramingRoundTrips() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] framed = RfcommFraming.encodeFrame(payload);

        // eaca9353 + len:4BE + 0002 + payload
        assertArrayEquals(RfcommFraming.MAGIC, Arrays.copyOfRange(framed, 0, 4));
        assertEquals(payload.length + 2, framed[7] & 0xFF); // big-endian length
        assertEquals(0x00, framed[8]);
        assertEquals(0x02, framed[9]);

        List<byte[]> out = new FrameReassembler().feed(framed);
        assertEquals(1, out.size());
        assertArrayEquals(payload, out.get(0));
    }

    @Test
    public void reassemblerHandlesSplitReads() {
        byte[] framed = RfcommFraming.encodeFrame("split me".getBytes(StandardCharsets.UTF_8));
        FrameReassembler r = new FrameReassembler();

        // A frame arriving across two socket reads must still emerge whole.
        assertEquals(0, r.feed(Arrays.copyOfRange(framed, 0, 6)).size());
        List<byte[]> out = r.feed(Arrays.copyOfRange(framed, 6, framed.length));
        assertEquals(1, out.size());
        assertArrayEquals("split me".getBytes(StandardCharsets.UTF_8), out.get(0));
    }

    @Test
    public void reassemblerResyncsPastGarbage() {
        byte[] framed = RfcommFraming.encodeFrame("after junk".getBytes(StandardCharsets.UTF_8));
        byte[] noisy = com.myvu.client.protocol.Pb.concat(
                new byte[] { 0x11, 0x22, 0x33 }, framed);

        List<byte[]> out = new FrameReassembler().feed(noisy);
        assertEquals(1, out.size());
        assertArrayEquals("after junk".getBytes(StandardCharsets.UTF_8), out.get(0));
    }

    @Test
    public void reassemblerEmitsMultipleFramesFromOneRead() {
        FrameReassembler r = new FrameReassembler();
        byte[] two = com.myvu.client.protocol.Pb.concat(
                RfcommFraming.encodeFrame(new byte[] { 1 }),
                RfcommFraming.encodeFrame(new byte[] { 2 }));

        List<byte[]> out = r.feed(two);
        assertEquals(2, out.size());
        assertArrayEquals(new byte[] { 1 }, out.get(0));
        assertArrayEquals(new byte[] { 2 }, out.get(1));
    }
}
