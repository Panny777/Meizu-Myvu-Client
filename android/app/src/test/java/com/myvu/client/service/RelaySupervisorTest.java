package com.myvu.client.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Exercises recovery sequences with a fake clock, without sleeps or Android. */
public class RelaySupervisorTest {
    private static final long BACKOFF = RelaySupervisor.backoffMs();

    private static final class Harness implements RelaySupervisor.Scheduler,
            RelaySupervisor.Delegate {
        long now;
        Runnable poll;
        boolean addressKnown = true;
        boolean inFlight;
        boolean ready;
        int connections;
        final RelaySupervisor supervisor = new RelaySupervisor(this, () -> now, this);

        Harness() {
            supervisor.start();
        }

        @Override
        public void postDelayed(Runnable runnable, long delayMs) {
            assertEquals(BACKOFF, delayMs);
            poll = runnable;
        }

        @Override
        public void removeCallbacks(Runnable runnable) {
            if (poll == runnable) poll = null;
        }

        @Override
        public boolean isRelayConnected() {
            return ready;
        }

        @Override
        public boolean canConnectRelay() {
            return addressKnown && !inFlight;
        }

        @Override
        public void connectRelay() {
            connections++;
            inFlight = true;
        }

        void tick() {
            now += BACKOFF;
            Runnable next = poll;
            poll = null;
            if (next != null) next.run();
        }

        void fail() {
            inFlight = false;
            ready = false;
            supervisor.onRelayLost();
        }

        void exhaustBudget() {
            supervisor.wake();
            for (int i = 1; i <= 6; i++) {
                assertEquals(i, connections);
                fail();
                tick();
            }
        }
    }

    @Test
    public void failuresAndRepeatedGlassesRequestsStopAfterSixAttempts() {
        Harness h = new Harness();
        h.exhaustBudget();
        for (int i = 0; i < 20; i++) {
            h.supervisor.wake();
            h.supervisor.onRelayLost();
            h.tick();
        }
        assertEquals(6, h.connections);
    }

    @Test
    public void pollsDuringHandshakesDoNotReplenishTheBudgetOrOverlapConnections() {
        Harness h = new Harness();
        h.supervisor.wake();
        for (int i = 1; i <= 6; i++) {
            for (int poll = 0; poll < 4; poll++) {
                h.tick();
                h.supervisor.wake();
                assertEquals(i, h.connections);
            }
            // The next poll can retry, but the six-attempt budget must
            // survive all the polls that waited for a handshake to finish.
            h.fail();
            h.tick();
        }
        h.tick();
        assertEquals(6, h.connections);
    }

    @Test
    public void explicitRetryRearmsExhaustedBudget() {
        Harness h = new Harness();
        h.exhaustBudget();
        h.supervisor.retry();
        assertEquals(7, h.connections);
        h.fail();
        h.tick();
        assertEquals(8, h.connections);
    }

    @Test
    public void explicitRequestsStillRespectBackoff() {
        Harness h = new Harness();
        h.supervisor.wake();
        h.fail();
        for (int i = 0; i < 20; i++) h.supervisor.retry();
        assertEquals(1, h.connections);
        h.now = BACKOFF - 1;
        h.supervisor.retry();
        assertEquals(1, h.connections);
        h.now++;
        h.supervisor.wake();
        assertEquals(2, h.connections);
    }

    @Test
    public void briefSuccessBeforeNextPollRestoresTheRecoveryBudget() {
        Harness h = new Harness();
        h.supervisor.wake();
        for (int i = 1; i < 6; i++) {
            h.fail();
            h.tick();
        }
        assertEquals(6, h.connections);
        h.inFlight = false;
        h.ready = true;
        h.supervisor.onRelayReady();
        // Lose the session before any polling callback can observe success.
        h.fail();
        for (int i = 1; i <= 6; i++) {
            h.tick();
            assertEquals(6 + i, h.connections);
            h.fail();
        }
        h.tick();
        assertEquals(12, h.connections);
    }

    @Test
    public void unknownAddressAndStoppedSupervisorDoNotConnect() {
        Harness h = new Harness();
        h.addressKnown = false;
        for (int i = 0; i < 8; i++) h.tick();
        assertEquals(0, h.connections);
        h.addressKnown = true;
        h.supervisor.wake();
        assertEquals(1, h.connections);
        h.supervisor.stop();
        assertNull(h.poll);
        h.fail();
        h.supervisor.retry();
        h.supervisor.wake();
        h.tick();
        assertEquals(1, h.connections);
    }
}
