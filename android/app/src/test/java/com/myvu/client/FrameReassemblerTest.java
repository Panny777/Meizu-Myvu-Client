package com.myvu.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.myvu.client.transport.bt.FrameReassembler;
import com.myvu.client.transport.bt.RfcommFraming;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * The RFCOMM length field is read straight off the wire, so it is either
 * attacker-controlled or -- far more likely on this link -- corrupted. Every
 * unchecked use of it was a fault:
 *
 *   negative / &lt; 2  -> Arrays.copyOfRange(from &gt; to) throws, killing the rx thread
 *   near MAX_VALUE  -> 8 + length overflows negative, same throw
 *   huge but valid  -> the frame never completes, so the buffer grows on every
 *                      read until OutOfMemoryError, which the rx loop's
 *                      catch(Exception) does NOT contain
 *
 * These tests pin the bound and, just as importantly, that a bad length makes
 * the parser RESYNC to the next real frame instead of stalling or dying.
 */
public class FrameReassemblerTest {

    // ------------------------------------------------------------- helpers

    /** magic + an arbitrary (possibly bogus) length + body -- no validation. */
    private static byte[] rawFrame(int length, byte[] body) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(RfcommFraming.MAGIC);
        o.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(length).array());
        o.write(body);
        return o.toByteArray();
    }

    private static byte[] concat(byte[]... parts) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p);
        return o.toByteArray();
    }

    private static byte[] payload(int n) {
        byte[] p = new byte[n];
        for (int i = 0; i < n; i++) p[i] = (byte) (i & 0xff);
        return p;
    }

    // ------------------------------------------------ happy path (no regression)

    @Test
    public void decodesAValidFrame() {
        byte[] p = payload(16);
        List<byte[]> out = new FrameReassembler().feed(RfcommFraming.encodeFrame(p));
        assertEquals(1, out.size());
        assertArrayEquals(p, out.get(0));
    }

    @Test
    public void reassemblesAcrossSplitReads() {
        FrameReassembler r = new FrameReassembler();
        byte[] p = payload(64);
        byte[] whole = RfcommFraming.encodeFrame(p);
        byte[] first = java.util.Arrays.copyOfRange(whole, 0, 5);
        byte[] rest = java.util.Arrays.copyOfRange(whole, 5, whole.length);

        assertTrue("incomplete frame yields nothing yet", r.feed(first).isEmpty());
        List<byte[]> out = r.feed(rest);
        assertEquals(1, out.size());
        assertArrayEquals(p, out.get(0));
    }

    @Test
    public void decodesTwoFramesInOneRead() throws Exception {
        byte[] a = payload(4);
        byte[] b = payload(9);
        List<byte[]> out = new FrameReassembler()
                .feed(concat(RfcommFraming.encodeFrame(a), RfcommFraming.encodeFrame(b)));
        assertEquals(2, out.size());
        assertArrayEquals(a, out.get(0));
        assertArrayEquals(b, out.get(1));
    }

    // --------------------------------------------- hostile / corrupt lengths

    @Test
    public void negativeLengthResyncsInsteadOfThrowing() throws Exception {
        byte[] good = RfcommFraming.encodeFrame(payload(8));
        List<byte[]> out = new FrameReassembler()
                .feed(concat(rawFrame(-1, payload(16)), good));
        assertEquals("recovers the following good frame", 1, out.size());
        assertArrayEquals(payload(8), out.get(0));
    }

    @Test
    public void zeroLengthResyncs() throws Exception {
        byte[] good = RfcommFraming.encodeFrame(payload(8));
        List<byte[]> out = new FrameReassembler()
                .feed(concat(rawFrame(0, new byte[0]), good));
        assertEquals(1, out.size());
        assertArrayEquals(payload(8), out.get(0));
    }

    @Test
    public void lengthBelowPrefixResyncs() throws Exception {
        // length 1 cannot carry the 2-byte PREFIX -- the old code threw here.
        byte[] good = RfcommFraming.encodeFrame(payload(8));
        List<byte[]> out = new FrameReassembler()
                .feed(concat(rawFrame(1, new byte[] { 0x00 }), good));
        assertEquals(1, out.size());
        assertArrayEquals(payload(8), out.get(0));
    }

    @Test
    public void oversizedLengthResyncs() throws Exception {
        byte[] good = RfcommFraming.encodeFrame(payload(8));
        List<byte[]> out = new FrameReassembler()
                .feed(concat(rawFrame(FrameReassembler.MAX_FRAME + 1, new byte[32]), good));
        assertEquals(1, out.size());
        assertArrayEquals(payload(8), out.get(0));
    }

    @Test
    public void integerOverflowLengthDoesNotThrow() throws Exception {
        // 8 + Integer.MAX_VALUE overflows to a negative total.
        byte[] good = RfcommFraming.encodeFrame(payload(8));
        List<byte[]> out = new FrameReassembler()
                .feed(concat(rawFrame(Integer.MAX_VALUE, new byte[32]), good));
        assertEquals(1, out.size());
        assertArrayEquals(payload(8), out.get(0));
    }

    @Test
    public void maxFrameSizedBodyIsStillAccepted() {
        // The bound must not reject legitimate (if large) traffic.
        byte[] p = payload(FrameReassembler.MAX_FRAME - RfcommFraming.PREFIX.length);
        List<byte[]> out = new FrameReassembler().feed(RfcommFraming.encodeFrame(p));
        assertEquals(1, out.size());
        assertArrayEquals(p, out.get(0));
    }

    /**
     * The OOM path: a bogus huge length used to make every subsequent read
     * accumulate into the buffer forever. Feeding megabytes after one must stay
     * bounded and still recover the next real frame.
     */
    @Test
    public void hugeLengthDoesNotAccumulateUnbounded() throws Exception {
        FrameReassembler r = new FrameReassembler();
        r.feed(rawFrame(Integer.MAX_VALUE - 8, new byte[0]));
        for (int i = 0; i < 64; i++) {
            assertTrue(r.feed(new byte[16 * 1024]).isEmpty()); // 1 MB of filler
        }
        List<byte[]> out = r.feed(RfcommFraming.encodeFrame(payload(12)));
        assertEquals("still resynchronises after the flood", 1, out.size());
        assertArrayEquals(payload(12), out.get(0));
    }
}
