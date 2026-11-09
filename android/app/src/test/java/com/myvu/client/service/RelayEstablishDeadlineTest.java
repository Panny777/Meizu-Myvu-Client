package com.myvu.client.service;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Deterministic phase/deadline regression tests without Android or sleeps. */
public class RelayEstablishDeadlineTest {
    private static final long WINDOW = 15000;

    private static final class Task {
        final Runnable runnable;
        final long when;
        Task(Runnable runnable, long when) {
            this.runnable = runnable;
            this.when = when;
        }
    }

    private static final class Harness implements RelayEstablishDeadline.Scheduler,
            RelayEstablishDeadline.Listener {
        long now;
        final List<Task> tasks = new ArrayList<>();
        final List<Object> expiredOwners = new ArrayList<>();
        final List<RelayEstablishDeadline.Phase> expiredPhases = new ArrayList<>();
        final RelayEstablishDeadline deadline =
                new RelayEstablishDeadline(this, WINDOW, WINDOW, this);

        @Override public void postDelayed(Runnable task, long delayMs) {
            tasks.add(new Task(task, now + delayMs));
        }

        @Override public void removeCallbacks(Runnable task) {
            tasks.removeIf(entry -> entry.runnable == task);
        }

        @Override public void onTimeout(Object owner, RelayEstablishDeadline.Phase phase) {
            expiredOwners.add(owner);
            expiredPhases.add(phase);
        }

        void advanceTo(long target) {
            while (true) {
                Task next = null;
                for (Task entry : tasks) {
                    if (entry.when <= target && (next == null || entry.when < next.when)) next = entry;
                }
                if (next == null) break;
                tasks.remove(next);
                now = next.when;
                next.runnable.run();
            }
            now = target;
        }
    }

    @Test public void slowSocketGetsFullHandshakeAndInitWindow() {
        Harness h = new Harness();
        Object socket = new Object();
        h.deadline.start(socket);
        h.advanceTo(11600);
        assertTrue(h.deadline.onConnected(socket));
        // The observed init has 29 entries paced 200 ms apart, after auth.
        // A shared 15-second timer used to terminate it before it finished.
        h.advanceTo(11600 + 500 + 29 * 200);
        assertTrue(h.expiredOwners.isEmpty());
        h.deadline.cancel(); // successful initialization
        h.advanceTo(60000);
        assertTrue(h.expiredOwners.isEmpty());
    }

    @Test public void stalledSocketRemainsBounded() {
        Harness h = new Harness();
        Object socket = new Object();
        h.deadline.start(socket);
        h.advanceTo(WINDOW - 1);
        assertTrue(h.expiredOwners.isEmpty());
        h.advanceTo(WINDOW);
        assertEquals(socket, h.expiredOwners.get(0));
        assertEquals(RelayEstablishDeadline.Phase.CONNECTING, h.expiredPhases.get(0));
        assertFalse(h.deadline.onConnected(socket));
    }

    @Test public void stalledHandshakeExpiresFromSocketConnection() {
        Harness h = new Harness();
        Object socket = new Object();
        h.deadline.start(socket);
        h.advanceTo(12000);
        assertTrue(h.deadline.onConnected(socket));
        h.advanceTo(26999);
        assertTrue(h.expiredOwners.isEmpty());
        h.advanceTo(27000);
        assertEquals(socket, h.expiredOwners.get(0));
        assertEquals(RelayEstablishDeadline.Phase.HANDSHAKE, h.expiredPhases.get(0));
    }

    @Test public void duplicateConnectedCallbackDoesNotExtendHandshake() {
        Harness h = new Harness();
        Object socket = new Object();
        h.deadline.start(socket);
        h.advanceTo(1000);
        assertTrue(h.deadline.onConnected(socket));
        h.advanceTo(10000);
        assertFalse(h.deadline.onConnected(socket));
        h.advanceTo(16000);
        assertEquals(1, h.expiredOwners.size());
    }

    @Test public void staleCallbacksDoNotAffectReplacement() {
        Harness h = new Harness();
        Object oldSocket = new Object();
        Object newSocket = new Object();
        h.deadline.start(oldSocket);
        Runnable queuedOldTimeout = h.tasks.get(0).runnable;
        h.advanceTo(1000);
        h.deadline.start(newSocket);
        assertFalse(h.deadline.onConnected(oldSocket));
        queuedOldTimeout.run(); // already dequeued when cancellation happened
        assertTrue(h.expiredOwners.isEmpty());
        h.advanceTo(16000);
        assertEquals(1, h.expiredOwners.size());
        assertEquals(newSocket, h.expiredOwners.get(0));
    }

    @Test public void previousPhaseTimeoutCannotAbortHandshake() {
        Harness h = new Harness();
        Object socket = new Object();
        h.deadline.start(socket);
        Runnable queuedConnectTimeout = h.tasks.get(0).runnable;
        h.advanceTo(1000);
        assertTrue(h.deadline.onConnected(socket));
        queuedConnectTimeout.run();
        assertTrue(h.expiredOwners.isEmpty());
        h.advanceTo(16000);
        assertEquals(RelayEstablishDeadline.Phase.HANDSHAKE, h.expiredPhases.get(0));
    }

    @Test public void cancelledQueuedTimeoutCannotReportFailure() {
        Harness h = new Harness();
        Object socket = new Object();
        h.deadline.start(socket);
        Runnable queued = h.tasks.get(0).runnable;
        h.deadline.cancel();
        queued.run();
        assertFalse(h.deadline.onConnected(socket));
        assertTrue(h.tasks.isEmpty());
        assertTrue(h.expiredOwners.isEmpty());
    }
}
