package com.myvu.client.ai;

import com.myvu.client.core.LogBus;
import com.myvu.client.protocol.Pb;
import com.myvu.client.protocol.PbValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Collects the glasses' microphone stream.
 *
 * The glasses capture audio themselves and push it to the phone as a run of
 * code:109 (CODE_RECORD_DATA_TRANS) messages -- one Opus packet each, carried in
 * protobuf field 5 of the StMessage envelope (the same slot
 * StarryNetMessage.setData uses).
 *
 * Format, taken from the official app's OpusDecoder: Opus, 16 kHz, mono, with
 * packets arriving at one of four discrete sizes (40, 83, 120, 240 bytes). Any
 * other length is something we do not understand and is counted, not decoded.
 *
 * This class only accumulates packets; decoding happens in OpusStream. Keeping
 * them separate means capture can be verified on its own.
 */
public class GlassesMicStream {

    /** Packet sizes the device actually emits, per OpusDecoder.Companion.a(). */
    private static final int[] KNOWN_PACKET_SIZES = { 40, 83, 120, 240 };

    /** Field 5 of the StMessage envelope carries the binary payload. */
    private static final int FIELD_AUDIO = 5;

    /** Guards against unbounded growth if an utterance never ends. */
    private static final int MAX_PACKETS = 2000; // ~40s at 20ms per packet

    private final List<byte[]> packets = new ArrayList<>();
    private boolean capturing;
    private volatile byte[] lastPacket;
    private final List<byte[]> justAdded = new ArrayList<>();
    private int unknownSizeCount;
    /** Distinct payload sizes seen, to learn what the device really sends. */
    private final java.util.Set<Integer> observedSizes = new java.util.TreeSet<>();

    /** Begins a new utterance, discarding anything previously buffered. */
    public void start() {
        packets.clear();
        lastPacket = null;
        justAdded.clear();
        unknownSizeCount = 0;
        observedSizes.clear();
        capturing = true;
    }

    public void stop() {
        capturing = false;
    }

    public boolean isCapturing() {
        return capturing;
    }

    public int packetCount() {
        return packets.size();
    }

    /**
     * Offers a code:109 relay body. Returns true if it contained audio.
     *
     * Safe to call when not capturing -- the glasses stream whenever they are
     * listening, which is not always when we want to record.
     */
    public boolean offer(byte[] relayBody) {
        byte[] field5 = extractAudio(relayBody);
        if (field5 == null) {
            // The caller already identified this as code:109, so failing here
            // means field 5 is not where we think the audio lives.
            rejected++;
            return false;
        }
        if (!capturing) return true; // recognised, but deliberately discarded

        // field 5 is NOT a raw Opus packet: it is one or more Opus frames each
        // prefixed with a 2-byte big-endian length, matching the official
        // encoder's pack format (OpusCodec byteList2ByteArr). Feeding the whole
        // blob -- length bytes included -- to MediaCodec corrupted every packet
        // and produced speech-shaped gibberish. Strip the prefixes here.
        justAdded.clear();
        int i = 0;
        while (i + 2 <= field5.length) {
            int len = ((field5[i] & 0xFF) << 8) | (field5[i + 1] & 0xFF);
            i += 2;
            if (len <= 0 || i + len > field5.length) {
                // Not the framing we expect; fall back to the raw blob so at
                // least something is captured, and record it for diagnosis.
                unknownSizeCount++;
                break;
            }
            byte[] frame = java.util.Arrays.copyOfRange(field5, i, i + len);
            i += len;

            if (packets.size() >= MAX_PACKETS) {
                LogBus.warn("glasses mic buffer full (" + MAX_PACKETS + ") -- stopping");
                capturing = false;
                break;
            }
            observedSizes.add(frame.length);
            packets.add(frame);
            justAdded.add(frame);
        }
        if (!justAdded.isEmpty()) lastPacket = justAdded.get(justAdded.size() - 1);
        return true;
    }

    /** The Opus frames extracted from the most recent payload. */
    public List<byte[]> justAdded() {
        return justAdded;
    }

    /** The most recently accepted frame, for incremental decoding. */
    public byte[] lastPacket() {
        return lastPacket;
    }

    /** The Opus packets captured so far, oldest first. */
    public List<byte[]> packets() {
        return new ArrayList<>(packets);
    }

    public int unknownSizeCount() {
        return unknownSizeCount;
    }

    /** The distinct payload sizes seen this utterance. */
    public java.util.Set<Integer> observedSizes() {
        return observedSizes;
    }

    /** Counts code:109 messages whose field 5 could not be read at all. */
    public int rejectedCount() {
        return rejected;
    }

    private int rejected;

    /** Pulls field 5 out of the StMessage envelope, or null if absent. */
    private byte[] extractAudio(byte[] relayBody) {
        try {
            Map<Integer, List<PbValue>> fields = Pb.parse(relayBody);
            if (!structureLogged) {
                structureLogged = true;
                StringBuilder sb = new StringBuilder("code:109 envelope fields:");
                for (Map.Entry<Integer, List<PbValue>> e : fields.entrySet()) {
                    PbValue v = e.getValue().get(0);
                    sb.append(' ').append(e.getKey()).append('=')
                      .append(v.isVarint() ? "varint" : (v.asBytes().length + "B"));
                }
                LogBus.log(sb.toString());
            }
            byte[] audio = Pb.firstBytes(fields, FIELD_AUDIO, null);
            return (audio != null && audio.length > 0) ? audio : null;
        } catch (Exception e) {
            // Inbound radio data; never let a malformed frame propagate.
            return null;
        }
    }

    /** Dumped once per process, purely to confirm where the audio actually is. */
    private boolean structureLogged;

    private static boolean isKnownSize(int length) {
        for (int size : KNOWN_PACKET_SIZES) {
            if (size == length) return true;
        }
        return false;
    }
}
