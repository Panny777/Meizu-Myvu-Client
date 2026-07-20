package com.myvu.client.protocol;

import com.myvu.client.core.Hex;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The captured init burst, ported from AppLayerMixin._load_init_script /
 * send_init_burst in myvu_client/myvu/applayer.py.
 *
 * WHY THIS EXISTS: the glasses' relay dispatcher does not fully wake up until
 * it has seen this opening run of app messages. Without the burst, later
 * messages are silently dropped -- no ACK, no visible effect -- even though the
 * channel is connected and the ability handshake succeeded. It is required on
 * EVERY transport (BLE and RFCOMM alike).
 *
 * Two transformations are applied to the capture:
 *  1. msgIds are renumbered into a fresh 1..N sequence. The glasses track the
 *     last received sequence number (0 on a fresh connect) and discard the
 *     capture's stale high msgIds as out-of-order.
 *  2. Messages replaying stale state are dropped -- SyncOffSetTime carries an
 *     old wall clock (it would set the glasses' clock backwards) and
 *     sync_clone_data an old settings snapshot; both fight the live defaults
 *     applied right after connect.
 */
public final class InitBurst {
    private InitBurst() {}

    public static final String ASSET_NAME = "captured_init.txt";

    /** One replayable message: the relay body plus the routing fields to reuse. */
    public static class Entry {
        public final String frame;
        public final byte[] msgBody;
        public final int needCallback;
        public final int category;
        public final int appUniteCode;

        Entry(String frame, byte[] msgBody, int needCallback, int category, int appUniteCode) {
            this.frame = frame;
            this.msgBody = msgBody;
            this.needCallback = needCallback;
            this.category = category;
            this.appUniteCode = appUniteCode;
        }

        public String bodyText() {
            return new String(msgBody, StandardCharsets.UTF_8);
        }
    }

    /**
     * Parses the capture and returns only the messages that should be replayed:
     * data frames (msgType == SEND) whose body is not stale state.
     */
    public static List<Entry> load(InputStream in) throws IOException {
        List<Entry> out = new ArrayList<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] parts = line.split("\t");
            if (parts.length < 3) continue;
            String frame = parts[0];
            byte[] content = Hex.decode(parts[2]);

            RelayMessage m = Relay.parseFrame(content);
            // Skip non-data frames (the capture contains one ACK); we ACK the
            // glasses' live messages dynamically instead of replaying that one.
            if (m == null || m.msgType != MsgType.SEND) continue;

            String bodyText = new String(m.msgBody, StandardCharsets.UTF_8);
            if (bodyText.contains("SyncOffSetTime") || bodyText.contains("sync_clone_data")) {
                continue;
            }
            out.add(new Entry(frame, m.msgBody, m.needCallback, m.category, m.appUniteCode));
        }
        return out;
    }
}
