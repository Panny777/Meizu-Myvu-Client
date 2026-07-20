package com.myvu.client.protocol.link;

/**
 * COMMAND enum from starry_link_encrypt.proto, confirmed against the decompiled
 * official app's Starry.StarryLinkEncrypt.COMMAND.
 */
public final class LinkCommands {
    private LinkCommands() {}

    public static final int CMD_INIT = 0;
    public static final int CMD_ENSURE = 1;
    public static final int CMD_UN_BONDED = 2;
    public static final int CMD_READ_SWITCH_KEY = 10;
    public static final int CMD_WRITE_SWITCH_KEY = 11;
    public static final int CMD_READ_SWITCH_INFO = 12;
    public static final int CMD_WRITE_SWITCH_INFO = 13;
    public static final int CMD_BOND_MSG_CHANGE = 14;
    public static final int CMD_AUTH_STATUE = 18;
    public static final int CMD_AUTH_MESSAGE = 19;

    /**
     * The classic-BT (RFCOMM) app-relay channel is NOT a fixed channel number:
     * the glasses generate a random 16-bit UUID per session and sync it to the
     * phone over BLE with this command before any SPP connect is attempted.
     * The "channel 13" seen in captures was just whatever channel SDP happened
     * to assign that session -- not a stable protocol constant.
     */
    public static final int CMD_SPP_SERVER_UUID_SYNC = 70;
    /** Glasses asking the phone to (re)open the relay -- drives RelaySupervisor. */
    public static final int CMD_SPP_SERVER_REQUEST_CONNECT = 71;
    public static final int CMD_SPP_SERVER_REQUEST_STATE_OPEN = 72;
    public static final int CMD_SPP_SERVER_REQUEST_STATE_CLOSE = 73;

    // ---- BTSTATUS enum (DeviceInfo.btStatus) ----
    // We currently always send DEFAULT(0). The real phone's value cannot be
    // recovered from a passive capture, since WRITE_SWITCH_INFO is encrypted
    // with an ECDH key whose private half was never captured.
    public static final int BTSTATUS_DEFAULT = 0;
    public static final int BTSTATUS_BOND = 1;
    public static final int BTSTATUS_BONDING = 2;
    public static final int BTSTATUS_NOBOND = 3;
    public static final int BTSTATUS_CONNECTED_ACL = 4;
    public static final int BTSTATUS_CONNECTED_HFP = 5;
    public static final int BTSTATUS_CONNECTED_A2DP = 6;
    public static final int BTSTATUS_DISCONNECTED = 7;
    public static final int BTSTATUS_NO_CONNECTED_BT = 8;
    public static final int BTSTATUS_EXIST_CONNECTED_BT = 9;
    public static final int BTSTATUS_CONNECT_FAIL = 10;
    public static final int BTSTATUS_BOND_CANCEL_OR_TIMEOUT = 11;
}
