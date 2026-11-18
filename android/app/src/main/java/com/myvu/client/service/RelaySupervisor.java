package com.myvu.client.service;

import android.os.Handler;
import android.os.SystemClock;

import com.myvu.client.core.LogBus;

import java.util.function.LongSupplier;

/**
 * Keeps the classic-BT app relay alive with a finite recovery budget.
 *
 * Polls and repeated glasses state messages share the same budget. Only a
 * completed relay session or an explicit fresh recovery request replenishes it;
 * a failed socket or an in-flight handshake is not a successful connection.
 */
public class RelaySupervisor {

    private static final long POLL_MS = 3000;
    private static final long BACKOFF_MS = 3000;
    private static final int MAX_ATTEMPTS = 6;

    public interface Delegate {
        /** True only after the relay's session handshake has completed. */
        boolean isRelayConnected();
        /** False while connecting or when the per-session UUID is unknown. */
        boolean canConnectRelay();
        void connectRelay();
    }

    /** Small scheduling seam so retry timing can be tested without Android. */
    interface Scheduler {
        void postDelayed(Runnable runnable, long delayMs);
        void removeCallbacks(Runnable runnable);
    }

    private final Scheduler scheduler;
    private final LongSupplier clock;
    private final Delegate delegate;

    private boolean running;
    private int attempt;
    private boolean exhaustionReported;
    private boolean hasAttempted;
    /** Monotonic time of the last attempt, retained across explicit requests. */
    private long lastAttemptAt;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            check();
            scheduler.postDelayed(this, POLL_MS);
        }
    };

    public RelaySupervisor(final Handler conn, Delegate delegate) {
        this(new Scheduler() {
            @Override
            public void postDelayed(Runnable runnable, long delayMs) {
                conn.postDelayed(runnable, delayMs);
            }

            @Override
            public void removeCallbacks(Runnable runnable) {
                conn.removeCallbacks(runnable);
            }
        }, SystemClock::elapsedRealtime, delegate);
    }

    RelaySupervisor(Scheduler scheduler, LongSupplier clock, Delegate delegate) {
        this.scheduler = scheduler;
        this.clock = clock;
        this.delegate = delegate;
    }

    public void start() {
        if (running) return;
        running = true;
        resetBudget();
        scheduler.postDelayed(poll, POLL_MS);
    }

    public void stop() {
        running = false;
        scheduler.removeCallbacks(poll);
    }

    /** Check promptly for repeated SPP connect/open messages, without rearming. */
    public void wake() {
        check();
    }

    /** A new relay UUID or an explicit user action starts a fresh recovery run. */
    public void retry() {
        if (!running) return;
        resetBudget();
        // Preserve lastAttemptAt: even a burst of fresh requests must respect
        // the hard rate limit instead of opening multiple sockets at once.
        check();
    }

    /** Record success immediately, even if the next drop precedes a poll. */
    public void onRelayReady() {
        if (running) resetBudget();
    }

    /** Failed attempts retain their cost; a subsequent poll may try again. */
    public void onRelayLost() {
        // Deliberately leave the budget unchanged and let the scheduled poll
        // retry. Disconnect callbacks can also arrive from replaced sockets;
        // they must not initiate another connection from inside that callback.
    }

    private void resetBudget() {
        attempt = 0;
        exhaustionReported = false;
    }

    private void check() {
        if (!running) return;
        if (delegate.isRelayConnected()) {
            resetBudget();
            return;
        }
        if (!delegate.canConnectRelay()) {
            // No UUID yet, or a connection/handshake is already in progress.
            // Waiting must neither spend nor replenish the recovery budget.
            return;
        }
        if (attempt >= MAX_ATTEMPTS) {
            if (!exhaustionReported) {
                exhaustionReported = true;
                LogBus.warn("relay reconnect gave up after " + MAX_ATTEMPTS
                        + " attempts; waiting for a new relay UUID or an explicit retry");
            }
            return;
        }
        long now = clock.getAsLong();
        if (hasAttempted && now - lastAttemptAt < BACKOFF_MS) return;

        attempt++;
        hasAttempted = true;
        lastAttemptAt = now;
        LogBus.log("app relay down -- reconnecting (attempt " + attempt
                + "/" + MAX_ATTEMPTS + ")");
        delegate.connectRelay();
    }

    /** Backoff spacing, exposed so the caller can schedule its own retry. */
    public static long backoffMs() {
        return BACKOFF_MS;
    }
}
