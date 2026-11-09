package com.myvu.client;

import com.myvu.client.app.AppLayer;
import com.myvu.client.app.feature.AutolockReadback;
import com.myvu.client.app.feature.SystemSettings;
import com.myvu.client.protocol.Pb;

import org.junit.Test;

import static org.junit.Assert.*;

public class AutolockReadbackTest {
    private byte[] envelope(String json) {
        // Sanitized reconstruction of the live launcher reply: fields 2/3
        // identify the launcher and field 4 carries a bare settings object.
        return Pb.concat(Pb.string(2, AppLayer.PKG_LAUNCHER),
                Pb.string(3, AppLayer.PKG_LAUNCHER), Pb.string(4, json),
                Pb.varintField(6, 181));
    }

    @Test public void decodesObservedSecondsWithoutEnumRounding() {
        assertEquals(Integer.valueOf(10), AutolockReadback.decode(
                envelope("{\"screen_off_time\":10}")));
        assertEquals(Integer.valueOf(120), AutolockReadback.decode(
                envelope("{\"screen_off_time\":120}")));
    }

    @Test public void ignoresOutgoingQueriesSettersAndNestedOccurrences() throws Exception {
        assertNull(AutolockReadback.decode(envelope(SystemSettings.query("get_screen_off_time"))));
        assertNull(AutolockReadback.decode(envelope(SystemSettings.setScreenOffTime(120))));
        assertNull(AutolockReadback.decode(envelope("{\"value\":{\"screen_off_time\":120}}")));
        assertNull(AutolockReadback.decode(envelope("{\"action\":\"get_screen_off_time\"}")));
    }

    @Test public void rejectsWrongTypesBoundsAndAmbiguousJson() {
        String[] invalid = {"null", "true", "\"120\"", "1.5", "1e2", "0", "-1",
                "2147483648", "999999999999999999999999999999999999"};
        for (String value : invalid) {
            assertNull(value, AutolockReadback.decode(envelope("{\"screen_off_time\":" + value + "}")));
        }
        assertNull(AutolockReadback.decode(envelope("{\"screen_off_time\":15,\"screen_off_time\":120}")));
        assertNull(AutolockReadback.decode(envelope("{\"screen_off_time\":120,\"action\":\"set\"}")));
        assertNull(AutolockReadback.decode(envelope("{\"screen_off_time\":120")));
    }

    @Test public void rejectsMissingWrongAndDuplicateEnvelopeFields() {
        byte[] reply = envelope("{\"screen_off_time\":120}");
        assertNull(AutolockReadback.decode(Pb.string(4, "{\"screen_off_time\":120}")));
        assertNull(AutolockReadback.decode(Pb.concat(Pb.string(2, "other.package"),
                Pb.string(3, AppLayer.PKG_LAUNCHER), Pb.string(4, "{\"screen_off_time\":120}"))));
        assertNull(AutolockReadback.decode(Pb.concat(reply, Pb.string(2, AppLayer.PKG_LAUNCHER))));
        assertNull(AutolockReadback.decode(Pb.concat(reply, Pb.string(4, "{\"screen_off_time\":15}"))));
        assertNull(AutolockReadback.decode(new byte[] {0x22, 0x7f, '{'}));
        assertNull(AutolockReadback.decode(null));
        assertNull(AutolockReadback.decode(new byte[0]));
    }

    @Test public void acceptsOnlyOneReportForAnExplicitReadOnTheSameSession() {
        AutolockReadback readback = new AutolockReadback();
        Object session = new Object();
        byte[] reply = envelope("{\"screen_off_time\":120}");
        assertFalse(readback.accept(session, reply, 100));
        readback.beginRead(session, 100);
        assertNull(readback.snapshot().seconds);
        assertFalse(readback.accept(new Object(), reply, 101));
        assertTrue(readback.accept(session, reply, 102));
        assertEquals(AutolockReadback.State.REPORTED, readback.snapshot().state);
        assertEquals(Integer.valueOf(120), readback.snapshot().seconds);
        assertFalse(readback.accept(session, envelope("{\"screen_off_time\":10}"), 103));
        assertEquals(Integer.valueOf(120), readback.snapshot().seconds);
    }

    @Test public void invalidationClearsReportsAndRejectsOutstandingOldSessionReplies() {
        AutolockReadback readback = new AutolockReadback();
        Object oldSession = new Object();
        Object replacement = new Object();
        byte[] reply = envelope("{\"screen_off_time\":10}");
        readback.beginRead(oldSession, 0);
        assertTrue(readback.accept(oldSession, reply, 1));
        readback.invalidate();
        assertEquals(AutolockReadback.State.UNKNOWN, readback.snapshot().state);
        assertNull(readback.snapshot().seconds);
        readback.beginRead(oldSession, 2);
        readback.invalidate(); // Same path used for Apply and connection replacement.
        assertFalse(readback.accept(oldSession, reply, 3));
        readback.beginRead(replacement, 4);
        assertFalse(readback.accept(oldSession, reply, 5));
        assertTrue(readback.accept(replacement, reply, 6));
    }

    @Test public void timesOutWithoutConvertingAnAckOrSetterIntoAReport() throws Exception {
        AutolockReadback readback = new AutolockReadback();
        Object session = new Object();
        readback.beginRead(session, 100);
        assertFalse(readback.accept(session, new byte[0], 101));
        assertFalse(readback.accept(session, envelope(SystemSettings.setScreenOffTime(120)), 102));
        assertFalse(readback.expire(5099));
        assertFalse(readback.accept(session, envelope("{\"screen_off_time\":120}"), 5100));
        assertEquals(AutolockReadback.State.TIMED_OUT, readback.snapshot().state);
        assertNull(readback.snapshot().seconds);
        readback.beginRead(session, 6000);
        assertFalse(readback.expire(6001)); // An old timeout cannot expire this read.
        assertTrue(readback.accept(session, envelope("{\"screen_off_time\":120}"), 6002));
    }

    @Test public void unrelatedRelayRetirementPreservesBleReadAndReport() {
        AutolockReadback readback = new AutolockReadback();
        Object bleSession = new Object();
        Object relaySession = new Object();
        readback.beginRead(bleSession, 0);
        readback.invalidateSession(relaySession);
        readback.invalidateSession(null);
        assertEquals(AutolockReadback.State.READING, readback.snapshot().state);
        assertTrue(readback.accept(bleSession, envelope("{\"screen_off_time\":120}"), 1));
        readback.invalidateSession(relaySession);
        assertEquals(AutolockReadback.State.REPORTED, readback.snapshot().state);
        assertEquals(Integer.valueOf(120), readback.snapshot().seconds);
        readback.invalidate(); // Full teardown still clears either transport.
        assertEquals(AutolockReadback.State.UNKNOWN, readback.snapshot().state);
        assertNull(readback.snapshot().seconds);
    }

    @Test public void owningSessionRetirementClearsPendingAndCompletedReadback() {
        AutolockReadback readback = new AutolockReadback();
        Object relaySession = new Object();
        byte[] reply = envelope("{\"screen_off_time\":120}");
        readback.beginRead(relaySession, 0);
        readback.invalidateSession(relaySession);
        assertFalse(readback.accept(relaySession, reply, 1));
        assertEquals(AutolockReadback.State.UNKNOWN, readback.snapshot().state);
        readback.beginRead(relaySession, 2);
        assertTrue(readback.accept(relaySession, reply, 3));
        readback.invalidateSession(relaySession);
        assertEquals(AutolockReadback.State.UNKNOWN, readback.snapshot().state);
        assertNull(readback.snapshot().seconds);
    }
}
