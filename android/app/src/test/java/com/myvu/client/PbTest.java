package com.myvu.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.myvu.client.protocol.Pb;
import com.myvu.client.protocol.PbValue;

import org.junit.Test;

import java.util.List;
import java.util.Map;

/**
 * The protobuf reader parses data straight off a radio from a device we do not
 * control, so most of these tests are about NOT crashing on malformed input.
 */
public class PbTest {

    @Test
    public void varintAndBytesRoundTrip() {
        byte[] msg = Pb.concat(
                Pb.varintField(1, 300),
                Pb.string(2, "hello"),
                Pb.varintField(3, 0));

        Map<Integer, List<PbValue>> f = Pb.parse(msg);
        assertEquals(300L, Pb.firstVarint(f, 1, -1));
        assertEquals("hello", Pb.firstString(f, 2, null));
        assertEquals(0L, Pb.firstVarint(f, 3, -1));
    }

    @Test
    public void repeatedFieldsAreAllRetained() {
        // Needed for the AI mic stream, which sends many field-5 audio chunks.
        byte[] msg = Pb.concat(
                Pb.bytes(5, new byte[] { 1 }),
                Pb.bytes(5, new byte[] { 2 }),
                Pb.bytes(5, new byte[] { 3 }));

        List<PbValue> all = Pb.all(Pb.parse(msg), 5);
        assertEquals(3, all.size());
        assertArrayEquals(new byte[] { 2 }, all.get(1).asBytes());
    }

    @Test
    public void largeVarintsSurvive() {
        long big = 1739000000000L; // epoch millis, as used in AUTH_SUCCESS
        assertEquals(big, Pb.firstVarint(Pb.parse(Pb.varintField(12, big)), 12, -1));
    }

    @Test
    public void fixed32And64AreKeptAsRawBytesInsteadOfThrowing() {
        // The old implementation threw on these wire types, which would have
        // taken the connection down on any unexpected inbound field.
        byte[] fixed32 = Pb.concat(new byte[] { (byte) ((1 << 3) | 5) }, new byte[] { 1, 2, 3, 4 });
        assertEquals(4, Pb.firstBytes(Pb.parse(fixed32), 1, new byte[0]).length);

        byte[] fixed64 = Pb.concat(new byte[] { (byte) ((2 << 3) | 1) },
                new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 });
        assertEquals(8, Pb.firstBytes(Pb.parse(fixed64), 2, new byte[0]).length);
    }

    @Test
    public void groupWireTypesStopTheParseCleanly() {
        // Wire type 3 (start group) means the buffer is almost certainly
        // misaligned. Python returns what it has; so do we.
        byte[] msg = Pb.concat(
                Pb.string(1, "kept"),
                new byte[] { (byte) ((2 << 3) | 3) },
                Pb.string(4, "dropped"));

        Map<Integer, List<PbValue>> f = Pb.parse(msg);
        assertEquals("kept", Pb.firstString(f, 1, null));
        assertNull("fields after a group marker are not decoded", Pb.first(f, 4));
    }

    @Test
    public void truncatedLengthDelimitedFieldDoesNotOverrun() {
        // Claims 200 bytes but supplies 3.
        byte[] msg = Pb.concat(new byte[] { (byte) ((1 << 3) | 2), (byte) 200 },
                new byte[] { 1, 2, 3 });
        assertTrue(Pb.parse(msg).isEmpty());
    }

    @Test
    public void truncatedVarintDoesNotOverrun() {
        // 0x80 sets the continuation bit with nothing following it.
        assertTrue(Pb.parse(new byte[] { (byte) ((1 << 3) | 0), (byte) 0x80 }).isEmpty());
    }

    @Test
    public void runawayContinuationBitsTerminate() {
        // Without a shift guard this would loop past the end of the buffer.
        byte[] evil = new byte[32];
        evil[0] = (byte) ((1 << 3) | 0);
        for (int i = 1; i < evil.length; i++) evil[i] = (byte) 0x80;
        assertTrue(Pb.parse(evil).isEmpty());
    }

    @Test
    public void emptyInputYieldsEmptyMap() {
        assertTrue(Pb.parse(new byte[0]).isEmpty());
    }

    @Test
    public void accessorsFallBackWhenTypeMismatches() {
        Map<Integer, List<PbValue>> f = Pb.parse(Pb.varintField(1, 5));
        // Field 1 is a varint; asking for bytes must return the default rather
        // than throwing at the call site.
        assertArrayEquals(new byte[0], Pb.firstBytes(f, 1, new byte[0]));
        assertEquals(-1L, Pb.firstVarint(f, 99, -1));
    }
}
