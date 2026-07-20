package com.myvu.client.protocol;

/** One decoded relay frame (Kotlin data class -> plain immutable holder). */
public class RelayMessage {
    public final int category;
    public final int msgType;
    public final int msgId;
    public final int needCallback;
    public final int appUniteCode;
    public final byte[] msgBody;

    public RelayMessage(int category, int msgType, int msgId, int needCallback,
                        int appUniteCode, byte[] msgBody) {
        this.category = category;
        this.msgType = msgType;
        this.msgId = msgId;
        this.needCallback = needCallback;
        this.appUniteCode = appUniteCode;
        this.msgBody = msgBody;
    }
}
