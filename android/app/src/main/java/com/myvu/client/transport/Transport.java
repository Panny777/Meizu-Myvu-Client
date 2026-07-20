package com.myvu.client.transport;

/**
 * A bidirectional link to the glasses carrying UNFRAMED protocol payloads.
 *
 * Deliberately narrow: BLE and classic-BT/RFCOMM apply completely different
 * framing (BLE fragments into little-endian sn-prefixed packets; RFCOMM wraps
 * in eaca9353 + big-endian length) to byte-identical payloads. Everything above
 * this interface -- the relay layer, the session handshake, every feature --
 * is transport-agnostic, which is exactly why the same Session/Relay code
 * serves both.
 *
 * Implementations report asynchronously through a {@link TransportListener};
 * no method here blocks on the remote device.
 */
public interface Transport {

    /** Begins connecting. Progress and failures arrive on the listener. */
    void connect();

    /**
     * Queues one payload for transmission. Safe to call from any thread;
     * implementations must not perform blocking I/O on the caller's thread.
     */
    void send(byte[] payload);

    /** Tears the link down. Idempotent; must not report a fault afterwards. */
    void close();

    boolean isConnected();

    /** Short human-readable name for logs, e.g. "RFCOMM" or "BLE". */
    String name();
}
