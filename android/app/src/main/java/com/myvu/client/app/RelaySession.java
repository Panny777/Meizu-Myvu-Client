package com.myvu.client.app;

import com.myvu.client.protocol.RelaySequencer;

/**
 * Per-transport relay state.
 *
 * BLE and RFCOMM each run their own independent RunAsOne session, exactly as
 * the Python client does with separate MyvuClient / MyvuRfcommClient objects.
 * Both the relay msgId sequence and the app msgId counter are per-connection:
 * the glasses track the last received sequence number and discard anything that
 * looks stale, so a reconnect MUST start from a fresh instance rather than
 * continuing the old numbering.
 */
public class RelaySession {
    public final RelaySequencer seq = new RelaySequencer();
    public final AppLayer appLayer = new AppLayer();

    /**
     * Set the first time we answer an ability reply.
     *
     * The glasses send the ability reply MORE THAN ONCE (observed twice on BLE).
     * Without this guard each copy triggered its own AUTH_SUCCESS and its own
     * init burst, so two bursts interleaved on one sequencer and the msgId run
     * stopped being the clean 1..N the glasses require.
     */
    public boolean authConfirmed;

    /** Set once the ability/AUTH_SUCCESS handshake and init burst have completed. */
    public boolean ready;
}
