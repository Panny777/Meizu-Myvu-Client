package com.myvu.client.app.feature;

import com.myvu.client.app.AppLayer;
import com.myvu.client.protocol.Pb;
import com.myvu.client.protocol.PbValue;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The observed launcher reply is a protobuf field 4 containing only
 * {"screen_off_time":10}. Keep this decoder narrow: a setter echo, an ACK or
 * a similarly named field inside unrelated JSON is not a settings readback.
 * Mutations run on the connection thread; the UI reads an immutable snapshot.
 */
public final class AutolockReadback {
    public static final long READ_TIMEOUT_MS = 5000;
    private static final Pattern REPLY = Pattern.compile(
            "\\s*\\{\\s*\"screen_off_time\"\\s*:\\s*([1-9][0-9]*)\\s*\\}\\s*");

    public enum State { UNKNOWN, READING, REPORTED, TIMED_OUT }

    public static final class Snapshot {
        public final State state;
        public final Integer seconds;

        private Snapshot(State state, Integer seconds) {
            this.state = state;
            this.seconds = seconds;
        }
    }

    private volatile Snapshot snapshot = new Snapshot(State.UNKNOWN, null);
    private Object ownerSession;
    private Object pendingSession;
    private long deadline;

    public Snapshot snapshot() { return snapshot; }

    public void beginRead(Object session, long nowMillis) {
        ownerSession = session;
        pendingSession = session;
        deadline = nowMillis + READ_TIMEOUT_MS;
        snapshot = new Snapshot(State.READING, null);
    }

    /** Apply, disconnect and replacement sessions invalidate previous reports. */
    public void invalidate() {
        ownerSession = null;
        pendingSession = null;
        snapshot = new Snapshot(State.UNKNOWN, null);
    }

    /** A relay restart must not discard a read or report carried over BLE. */
    public void invalidateSession(Object retiredSession) {
        if (retiredSession != null && ownerSession == retiredSession) invalidate();
    }

    public boolean expire(long nowMillis) {
        if (pendingSession == null || nowMillis < deadline) return false;
        pendingSession = null;
        snapshot = new Snapshot(State.TIMED_OUT, null);
        return true;
    }

    /**
     * Replies have no demonstrated request ID. Accept only during an explicit
     * read on the same session, and describe the result as last reported rather
     * than claiming that an earlier setting change was applied successfully.
     */
    public boolean accept(Object session, byte[] body, long nowMillis) {
        if (pendingSession == null || pendingSession != session) return false;
        if (expire(nowMillis)) return false;
        Integer seconds = decode(body);
        if (seconds == null) return false;
        pendingSession = null;
        snapshot = new Snapshot(State.REPORTED, seconds);
        return true;
    }

    public static Integer decode(byte[] body) {
        if (body == null) return null;
        Map<Integer, List<PbValue>> fields;
        try {
            fields = Pb.parse(body);
        } catch (RuntimeException e) {
            return null; // Malformed radio data must not interrupt the connection.
        }
        // Duplicate routing or JSON fields are ambiguous, so do not pick one.
        if (Pb.all(fields, 2).size() != 1 || Pb.all(fields, 3).size() != 1
                || Pb.all(fields, 4).size() != 1) return null;
        if (!AppLayer.PKG_LAUNCHER.equals(Pb.firstString(fields, 2, ""))
                || !AppLayer.PKG_LAUNCHER.equals(Pb.firstString(fields, 3, ""))) return null;
        Matcher match = REPLY.matcher(Pb.firstString(fields, 4, ""));
        if (!match.matches()) return null;
        try {
            // Positive integer seconds are the observed representation. This
            // bound prevents overflow; it does not claim a firmware option list.
            return Integer.valueOf(match.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
