package com.myvu.client.protocol;

import java.util.Arrays;

/**
 * Faithful port of myvu_client/myvu/relay.py (RunAsOne relay / SuperMessage layer).
 */
public final class Relay {
    private Relay() {}

    public static final int FRAME_PREFIX = 0x01;
    public static final int DEFAULT_CATEGORY = 3;

    /** Returns null if the buffer is not a relay frame. */
    public static RelayMessage parseFrame(byte[] raw) {
        if (raw.length == 0 || (raw[0] & 0xFF) != FRAME_PREFIX) return null;
        TlvBox outer = TlvBox.parse(Arrays.copyOfRange(raw, 1, raw.length));
        Integer cat = outer.getByte(TlvTags.CATEGORY);
        byte[] payload = outer.getBytes(TlvTags.PAYLOAD);
        if (payload == null) return null;
        TlvBox inner = TlvBox.parse(payload);
        return new RelayMessage(
                cat != null ? cat : DEFAULT_CATEGORY,
                orZero(inner.getByte(TlvTags.MSG_TYPE)),
                orZero(inner.getInt(TlvTags.MSG_ID)),
                orZero(inner.getByte(TlvTags.NEED_CALLBACK)),
                orZero(inner.getByte(TlvTags.APP_UNITE_CODE)),
                inner.getBytes(TlvTags.MSG_BODY) != null
                        ? inner.getBytes(TlvTags.MSG_BODY) : new byte[0]);
    }

    private static int orZero(Integer v) { return v != null ? v : 0; }

    public static byte[] buildFrame(int category, int msgType, int msgId, int needCallback,
                                    int appUniteCode, byte[] msgBody) {
        TlvBox inner = new TlvBox();
        inner.putByte(TlvTags.MSG_TYPE, msgType);
        inner.putInt(TlvTags.MSG_ID, msgId);
        inner.putByte(TlvTags.NEED_CALLBACK, needCallback);
        inner.putByte(TlvTags.APP_UNITE_CODE, appUniteCode);
        if (msgBody.length > 0) inner.putBytes(TlvTags.MSG_BODY, msgBody);
        TlvBox outer = new TlvBox();
        outer.putByte(TlvTags.CATEGORY, category);
        outer.putBox(TlvTags.PAYLOAD, inner);
        return Pb.concat(new byte[] { (byte) FRAME_PREFIX }, outer.serialize());
    }
}
