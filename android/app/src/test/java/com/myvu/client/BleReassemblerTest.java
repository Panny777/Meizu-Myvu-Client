package com.myvu.client;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.myvu.client.transport.ble.BleReassembler;
import com.myvu.client.transport.ble.Uuids;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class BleReassemblerTest {

    @Test
    public void fragmentsAreJoinedInSequenceOrder() {
        BleReassembler r = new BleReassembler();
        r.start(3, 16);

        assertNull(r.add(1, "aaa".getBytes(StandardCharsets.UTF_8)));
        assertNull(r.add(2, "bbb".getBytes(StandardCharsets.UTF_8)));
        byte[] full = r.add(3, "ccc".getBytes(StandardCharsets.UTF_8));

        assertNotNull(full);
        assertEquals("aaabbbccc", new String(full, StandardCharsets.UTF_8));
    }

    @Test
    public void outOfOrderFragmentsStillJoinCorrectly() {
        // BLE delivery order is not guaranteed, so ordering must come from the
        // sequence number rather than arrival order.
        BleReassembler r = new BleReassembler();
        r.start(3, 16);

        assertNull(r.add(3, "ccc".getBytes(StandardCharsets.UTF_8)));
        assertNull(r.add(1, "aaa".getBytes(StandardCharsets.UTF_8)));
        byte[] full = r.add(2, "bbb".getBytes(StandardCharsets.UTF_8));

        assertNotNull(full);
        assertEquals("aaabbbccc", new String(full, StandardCharsets.UTF_8));
    }

    @Test
    public void mixCtrHeaderPrecedesFragments() {
        BleReassembler r = new BleReassembler();
        r.start(2, 0, "HEAD".getBytes(StandardCharsets.UTF_8));

        assertNull(r.add(1, "one".getBytes(StandardCharsets.UTF_8)));
        byte[] full = r.add(2, "two".getBytes(StandardCharsets.UTF_8));

        assertEquals("HEADonetwo", new String(full, StandardCharsets.UTF_8));
    }

    @Test
    public void activeFlagTracksAMessageInFlight() {
        // The DMTU deferral in BleMessageChannel keys off this: chunk size must
        // not change mid-message.
        BleReassembler r = new BleReassembler();
        assertFalse(r.isActive());

        r.start(2, 16);
        assertTrue(r.isActive());
        r.add(1, new byte[] { 1 });
        assertTrue(r.isActive());
        r.add(2, new byte[] { 2 });
        assertFalse(r.isActive());
    }

    @Test
    public void resetClearsPartialState() {
        BleReassembler r = new BleReassembler();
        r.start(2, 16);
        r.add(1, "stale".getBytes(StandardCharsets.UTF_8));
        r.reset();

        r.start(1, 0);
        byte[] full = r.add(1, "fresh".getBytes(StandardCharsets.UTF_8));
        assertEquals("fresh", new String(full, StandardCharsets.UTF_8));
    }

    @Test
    public void pkgTypeSurvivesUntilDelivery() {
        BleReassembler r = new BleReassembler();
        r.start(1, 17);
        assertEquals(17, r.pkgType());
        r.add(1, new byte[] { 1 });
        assertEquals("pkgType must still be readable when the message completes",
                17, r.pkgType());
    }

    @Test
    public void uuidHelperMatchesTheAppsFormat() {
        assertEquals("00000bd1-0000-1000-8000-00805f9b34fb", Uuids.SERVICE.toString());
        assertEquals("00002020-0000-1000-8000-00805f9b34fb", Uuids.AIR_INTERNAL.toString());
        assertEquals("00002021-0000-1000-8000-00805f9b34fb", Uuids.AIR_EXTERNAL.toString());
        assertEquals("00002022-0000-1000-8000-00805f9b34fb", Uuids.AIR_URGENT.toString());
    }

    @Test
    public void channelSetsArePrioritisedAirThenV2() {
        assertEquals(2, Uuids.CHANNEL_SETS.length);
        assertArrayEquals(new java.util.UUID[] {
                Uuids.AIR_INTERNAL, Uuids.AIR_EXTERNAL, Uuids.AIR_URGENT
        }, Uuids.CHANNEL_SETS[0]);
        // The urgent characteristic is always internal+2 within a family.
        assertEquals(Uuids.make(0x2012), Uuids.CHANNEL_SETS[1][2]);
    }
}
