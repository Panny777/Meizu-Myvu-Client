package com.myvu.client.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Faithful port of myvu_client/myvu/tlv.py (TlvBox codec).
 * Wire format (big-endian): concatenation of [tag:1][length:2][value].
 */
public class TlvBox {
    public final LinkedHashMap<Integer, byte[]> values = new LinkedHashMap<>();

    public TlvBox putBytes(int tag, byte[] v) { values.put(tag, v); return this; }

    public TlvBox putByte(int tag, int v) {
        values.put(tag, new byte[] { (byte) (v & 0xFF) });
        return this;
    }

    public TlvBox putInt(int tag, int v) {
        ByteBuffer bb = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(v);
        values.put(tag, bb.array());
        return this;
    }

    public TlvBox putBox(int tag, TlvBox box) { values.put(tag, box.serialize()); return this; }

    public byte[] getBytes(int tag) { return values.get(tag); }

    /** Returns null when the tag is absent, so callers can supply a default. */
    public Integer getByte(int tag) {
        byte[] v = values.get(tag);
        if (v == null || v.length == 0) return null;
        return v[0] & 0xFF;
    }

    public Integer getInt(int tag) {
        byte[] v = values.get(tag);
        if (v == null || v.length != 4) return null;
        return ByteBuffer.wrap(v).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<Integer, byte[]> e : values.entrySet()) {
            byte[] v = e.getValue();
            out.write(e.getKey() & 0xFF);
            out.write((v.length >> 8) & 0xFF);
            out.write(v.length & 0xFF);
            try {
                out.write(v);
            } catch (IOException impossible) {
                throw new AssertionError(impossible); // ByteArrayOutputStream never throws
            }
        }
        return out.toByteArray();
    }

    public static TlvBox parse(byte[] data) {
        TlvBox box = new TlvBox();
        int i = 0;
        int n = data.length;
        while (i + 3 <= n) {
            int tag = data[i] & 0xFF;
            int length = ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            i += 3;
            if (i + length > n) break;
            box.values.put(tag, Arrays.copyOfRange(data, i, i + length));
            i += length;
        }
        return box;
    }
}
