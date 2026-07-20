package com.myvu.client.protocol.link;

/** A decoded LinkProtocol message: {1:deviceId, 2:cmd, 3:data}. */
public class LinkMessage {
    public final byte[] deviceId;
    public final int cmd;
    public final byte[] data;

    public LinkMessage(byte[] deviceId, int cmd, byte[] data) {
        this.deviceId = deviceId;
        this.cmd = cmd;
        this.data = data;
    }
}
