package com.myvu.client.protocol;

/** Owns the outgoing msgId counter (starts at 1) and builds/ACKs frames. */
public class RelaySequencer {
    private int outId = 0;
    public int lastRecvId = 0;

    public int getOutId() { return outId; }

    public int nextId() { outId += 1; return outId; }

    public byte[] dataFrame(byte[] msgBody) {
        return dataFrame(msgBody, Relay.DEFAULT_CATEGORY, 1, 1);
    }

    public byte[] dataFrame(byte[] msgBody, int category, int needCallback, int appUniteCode) {
        return Relay.buildFrame(category, MsgType.SEND, nextId(), needCallback, appUniteCode, msgBody);
    }

    public byte[] ackFrame(RelayMessage forMsg) {
        TlvBox inner = new TlvBox();
        inner.putByte(TlvTags.MSG_TYPE, MsgType.SEND_SUCCESS);
        inner.putInt(TlvTags.MSG_ID, forMsg.msgId);
        TlvBox outer = new TlvBox();
        outer.putByte(TlvTags.CATEGORY, forMsg.category);
        outer.putBox(TlvTags.PAYLOAD, inner);
        return Pb.concat(new byte[] { (byte) Relay.FRAME_PREFIX }, outer.serialize());
    }
}
