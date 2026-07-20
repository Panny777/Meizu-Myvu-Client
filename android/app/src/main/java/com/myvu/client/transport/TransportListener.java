package com.myvu.client.transport;

/**
 * Callbacks from a {@link Transport}. All are delivered on the connection
 * thread (myvu-conn), never on a socket or binder thread, so implementations
 * can touch protocol state without locking.
 */
public interface TransportListener {

    void onConnected(Transport transport);

    /** One complete, de-framed protocol payload. */
    void onPayload(Transport transport, byte[] payload);

    /**
     * The link went down. {@code cause} is null for an orderly local close.
     * Fires at most once per connect.
     */
    void onDisconnected(Transport transport, Throwable cause);
}
