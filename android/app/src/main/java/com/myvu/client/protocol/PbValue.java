package com.myvu.client.protocol;

import java.nio.charset.StandardCharsets;

/**
 * One decoded protobuf field value.
 *
 * Python's linkproto.pb_parse puts heterogeneous types in one dict (ints for
 * varints, bytes for length-delimited). Java needs a tagged union instead --
 * the previous approach of re-encoding varints as 8-byte big-endian blobs was
 * lossy at the call site and easy to misread.
 */
public final class PbValue {
    private final long varint;
    private final byte[] bytes;
    private final boolean isVarint;

    private PbValue(long varint, byte[] bytes, boolean isVarint) {
        this.varint = varint;
        this.bytes = bytes;
        this.isVarint = isVarint;
    }

    public static PbValue ofVarint(long v) { return new PbValue(v, null, true); }
    public static PbValue ofBytes(byte[] v) { return new PbValue(0L, v, false); }

    public boolean isVarint() { return isVarint; }

    public long asVarint() {
        if (!isVarint) throw new IllegalStateException("field is length-delimited, not a varint");
        return varint;
    }

    public byte[] asBytes() {
        if (isVarint) throw new IllegalStateException("field is a varint, not length-delimited");
        return bytes;
    }

    public String asString() {
        return new String(asBytes(), StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return isVarint ? ("varint:" + varint) : ("bytes[" + bytes.length + "]");
    }
}
