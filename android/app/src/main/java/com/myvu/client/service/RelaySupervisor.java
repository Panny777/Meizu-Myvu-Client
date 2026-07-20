package com.myvu.client.service;

import android.os.Handler;

import com.myvu.client.core.LogBus;

/**
 * Keeps the classic-BT app relay alive.
 *
 * The glasses own the relay's lifecycle: they drop it when idle and ask for it
 * back with CMD_SPP_SERVER_REQUEST_CONNECT. Port of run_glasses.relay_supervisor
 * -- a 3s poll (so silent drops are noticed too) plus an explicit wake on that
 * command, with a bounded retry run.
 */
public class RelaySupervisor {

    private static final long POLL_MS = 3000;
    private static final long BACKOFF_MS = 3000;
    private static final int MAX_ATTEMPTS = 6;

    public interface Delegate {
        /** True when the relay is up and does not need attention. */
        boolean isRelayConnected();
        /** True when we know where the relay lives (the per-session UUID). */
        boolean canConnectRelay();
        void connectRelay();
    }

    private final Handler conn;
    private final Delegate delegate;

    private boolean running;
    private int attempt;
    /** Wall clock of the last connect attempt, for rate limiting. */
    private long lastAttemptAt;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            check();
            conn.postDelayed(this, POLL_MS);
        }
    };

    public RelaySupervisor(Handler conn, Delegate delegate) {
        this.conn = conn;
        this.delegate = delegate;
    }

    public void start() {
        if (running) return;
        running = true;
        attempt = 0;
        conn.postDelayed(poll, POLL_MS);
    }

    public void stop() {
        running = false;
        conn.removeCallbacks(poll);
    }

    /** Called when the glasses explicitly ask for the relay (cmd 71). */
    public void wake() {
        if (!running) return;
        // A fresh request means the glasses want it NOW, so reset the retry
        // budget rather than staying in a backed-off state.
        attempt = 0;
        check();
    }

    /** Called when the relay drops, so the next poll retries promptly. */
    public void onRelayLost() {
        attempt = 0;
    }

    private void check() {
        if (!running) return;
        if (delegate.isRelayConnected()) {
            attempt = 0;
            return;
        }
        if (!delegate.canConnectRelay()) {
            // No UUID yet; the glasses will sync one over BLE shortly.
            return;
        }
        // Hard rate limit. The glasses emit bursts of state-change messages and
        // each one wakes us; without this the retries collapsed into a tight
        // connect/close loop that hammered the device.
        long now = System.currentTimeMillis();
        if (now - lastAttemptAt < BACKOFF_MS) return;
        if (attempt >= MAX_ATTEMPTS) return;

        attempt++;
        lastAttemptAt = now;
        LogBus.log("app relay down -- reconnecting (attempt " + attempt
                + "/" + MAX_ATTEMPTS + ")");
        delegate.connectRelay();

        if (attempt >= MAX_ATTEMPTS) {
            LogBus.warn("relay reconnect gave up after " + MAX_ATTEMPTS
                    + " attempts; will retry when the glasses ask again");
        }
    }

    /** Backoff spacing, exposed so the caller can schedule its own retry. */
    public static long backoffMs() {
        return BACKOFF_MS;
    }
}
