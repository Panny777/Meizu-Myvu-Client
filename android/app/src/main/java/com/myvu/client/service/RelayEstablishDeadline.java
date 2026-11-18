package com.myvu.client.service;

/**
 * Gives socket connection and session initialization separate bounded windows.
 * All methods run on the connection thread. Owner identity and callback identity
 * keep a cancelled phase from timing out a replacement connection.
 */
final class RelayEstablishDeadline {
    enum Phase { CONNECTING, HANDSHAKE }

    interface Scheduler {
        void postDelayed(Runnable task, long delayMs);
        void removeCallbacks(Runnable task);
    }

    interface Listener {
        void onTimeout(Object owner, Phase phase);
    }

    private final Scheduler scheduler;
    private final Listener listener;
    private final long connectMs;
    private final long handshakeMs;
    private Object owner;
    private Phase phase;
    private Runnable pending;

    RelayEstablishDeadline(Scheduler scheduler, long connectMs, long handshakeMs,
                           Listener listener) {
        this.scheduler = scheduler;
        this.connectMs = connectMs;
        this.handshakeMs = handshakeMs;
        this.listener = listener;
    }

    void start(Object owner) {
        cancel();
        this.owner = owner;
        phase = Phase.CONNECTING;
        schedule(connectMs);
    }

    /** Returns false for stale or duplicate socket callbacks. */
    boolean onConnected(Object source) {
        if (source != owner || phase != Phase.CONNECTING) return false;
        scheduler.removeCallbacks(pending);
        phase = Phase.HANDSHAKE;
        schedule(handshakeMs);
        return true;
    }

    void cancel() {
        if (pending != null) scheduler.removeCallbacks(pending);
        pending = null;
        owner = null;
        phase = null;
    }

    private void schedule(long delayMs) {
        pending = new Runnable() {
            @Override public void run() {
                // A removed callback may already be queued. It must not act on
                // the current owner or phase after cancellation/replacement.
                if (pending != this) return;
                Object expiredOwner = owner;
                Phase expiredPhase = phase;
                cancel();
                listener.onTimeout(expiredOwner, expiredPhase);
            }
        };
        scheduler.postDelayed(pending, delayMs);
    }
}
