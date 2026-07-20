package com.myvu.client.service;

/** Coarse connection state, for the UI and the reconnect logic. */
public enum ConnectionState {
    IDLE,
    /** BR/EDR bonding. Only reachable after BLE is up -- see ConnectionManager. */
    BONDING,
    /** BLE GATT connect, MTU exchange, service discovery, subscriptions. */
    CONNECTING,
    /** Version negotiation and the ECDH bond on the link characteristic. */
    PAIRING,
    /** RunAsOne ability/AUTH_SUCCESS handshake and the init burst. */
    SESSION,
    /** Handshake done, init burst sent: the app layer is live. */
    READY,
    FAILED
}
