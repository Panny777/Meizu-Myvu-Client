package com.myvu.client.protocol;

/** Values for TlvTags.MSG_TYPE. */
public final class MsgType {
    private MsgType() {}

    public static final int OPEN = 1;
    public static final int CLOSE = 2;
    public static final int SEND = 3;
    public static final int SEND_SUCCESS = 4;
    public static final int SEND_FAIL = 5;
    public static final int OPEN_SUCCESS = 6;
    public static final int OPEN_FAIL = 7;
    public static final int OPEN_PAGE = 8;
}
