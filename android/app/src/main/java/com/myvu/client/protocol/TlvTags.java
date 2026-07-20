package com.myvu.client.protocol;

/** Tag numbers for the TlvBox codec (see TlvBox). */
public final class TlvTags {
    private TlvTags() {}

    public static final int MSG_TYPE = 100;
    public static final int MSG_ID = 101;
    public static final int ERROR_CODE = 102;
    public static final int NEED_CALLBACK = 103;
    public static final int OPEN_TYPE = 104;
    public static final int MSG_BODY = 105;
    public static final int HOST = 106;
    public static final int VERSION = 107;
    public static final int DEST_ID = 108;
    public static final int APP_UNITE_CODE = 109;
    public static final int LISTENER_ID = 110;
    public static final int SELF_ID = 111;
    public static final int CATEGORY = 112;
    public static final int PAYLOAD = 113;
}
