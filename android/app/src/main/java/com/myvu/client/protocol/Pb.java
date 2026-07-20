package com.myvu.client.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal protobuf writer + reader, ported from myvu_client/myvu/linkproto.py
 * (pb_bytes/pb_varint/pb_string/pb_parse). Not a general protobuf library --
 * only what this protocol needs.
 *
 * The reader is deliberately lenient in the same places the Python one is:
 * inbound data comes off a radio from a device we do not control, so a
 * malformed message must never take the connection down.
 */
public final class Pb {
    private Pb() {}

    // ------------------------------------------------------------- writers

    private static byte[] varint(long n) {
        long value = n;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int b = (int) (value & 0x7F);
            value >>>= 7;
            if (value != 0L) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return out.toByteArray();
            }
        }
    }

    private static byte[] tag(int field, int wire) {
        return varint(((long) field << 3) | (long) wire);
    }

    public static byte[] bytes(int field, byte[] v) {
        return concat(tag(field, 2), varint(v.length), v);
    }

    public static byte[] varintField(int field, long v) {
        return concat(tag(field, 0), varint(v));
    }

    public static byte[] string(int field, String v) {
        return bytes(field, v.getBytes(StandardCharsets.UTF_8));
    }

    /** Convenience for the `a + b + c` byte-array concatenation Python gave us for free. */
    public static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    // -------------------------------------------------------------- reader

    /**
     * Parse into {fieldNumber: [values]}, mirroring linkproto.pb_parse.
     *
     * Wire types 0 (varint) and 2 (length-delimited) are the ones this protocol
     * actually uses. Types 1 (fixed64) and 5 (fixed32) are kept as raw bytes so
     * an unexpected field cannot crash the reader. Types 3/4 (deprecated groups)
     * stop the parse and return what was decoded so far, exactly as Python does
     * -- hitting one almost always means the buffer is misaligned rather than
     * that a group is genuinely present.
     */
    public static Map<Integer, List<PbValue>> parse(byte[] data) {
        Map<Integer, List<PbValue>> out = new HashMap<>();
        int i = 0;
        while (i < data.length) {
            long[] kv = readVarint(data, i);
            if (kv == null) break;
            long key = kv[0];
            i = (int) kv[1];
            int field = (int) (key >> 3);
            int wire = (int) (key & 7L);

            PbValue value;
            switch (wire) {
                case 0: {
                    long[] r = readVarint(data, i);
                    if (r == null) return out;
                    i = (int) r[1];
                    value = PbValue.ofVarint(r[0]);
                    break;
                }
                case 2: {
                    long[] r = readVarint(data, i);
                    if (r == null) return out;
                    int len = (int) r[0];
                    i = (int) r[1];
                    if (len < 0 || i + len > data.length) return out; // truncated
                    value = PbValue.ofBytes(Arrays.copyOfRange(data, i, i + len));
                    i += len;
                    break;
                }
                case 5: {
                    if (i + 4 > data.length) return out;
                    value = PbValue.ofBytes(Arrays.copyOfRange(data, i, i + 4));
                    i += 4;
                    break;
                }
                case 1: {
                    if (i + 8 > data.length) return out;
                    value = PbValue.ofBytes(Arrays.copyOfRange(data, i, i + 8));
                    i += 8;
                    break;
                }
                default:
                    // wire types 3/4 (groups), or anything else: stop cleanly.
                    return out;
            }

            List<PbValue> list = out.get(field);
            if (list == null) {
                list = new ArrayList<>(1);
                out.put(field, list);
            }
            list.add(value);
        }
        return out;
    }

    // ---------------------------------------------------- field accessors

    /** Every value seen for {@code field} -- needed for repeated fields (e.g. mic audio chunks). */
    public static List<PbValue> all(Map<Integer, List<PbValue>> f, int field) {
        List<PbValue> v = f.get(field);
        return v != null ? v : Collections.<PbValue>emptyList();
    }

    public static PbValue first(Map<Integer, List<PbValue>> f, int field) {
        List<PbValue> v = f.get(field);
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }

    /** First length-delimited value for {@code field}, or {@code def} if absent/wrong type. */
    public static byte[] firstBytes(Map<Integer, List<PbValue>> f, int field, byte[] def) {
        PbValue v = first(f, field);
        return (v != null && !v.isVarint()) ? v.asBytes() : def;
    }

    /** First varint value for {@code field}, or {@code def} if absent/wrong type. */
    public static long firstVarint(Map<Integer, List<PbValue>> f, int field, long def) {
        PbValue v = first(f, field);
        return (v != null && v.isVarint()) ? v.asVarint() : def;
    }

    /** First length-delimited value decoded as UTF-8, or {@code def} if absent/wrong type. */
    public static String firstString(Map<Integer, List<PbValue>> f, int field, String def) {
        PbValue v = first(f, field);
        return (v != null && !v.isVarint()) ? v.asString() : def;
    }

    /**
     * Returns {value, nextIndex}, or null if the buffer ends mid-varint.
     * Bails out past 64 bits of shift too: without that guard a run of 0x80
     * bytes would spin and read off the end.
     */
    private static long[] readVarint(byte[] data, int start) {
        int shift = 0;
        long result = 0L;
        int i = start;
        while (true) {
            if (i >= data.length || shift > 63) return null;
            int b = data[i] & 0xFF;
            i += 1;
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) return new long[] { result, i };
            shift += 7;
        }
    }
}
