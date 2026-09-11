package org.aethercode.tasks.supervisor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * prior round (T-313): the 3-strike auto-restart policy. Verifies
 * the backoff schedule, the window reset on success, and the
 * "ask user" hook on the 4th consecutive failure.
 */
class AutoRestartPolicyTest {

    @Test
    void firstFailureSchedulesRestartWithBackoff() {
        AtomicInteger restarts = new AtomicInteger();
        AtomicInteger giveUps = new AtomicInteger();
        AutoRestartPolicy p = new AutoRestartPolicy(3, 60_000L,
                ms -> restarts.incrementAndGet(),
                giveUps::incrementAndGet);
        long backoff = p.recordFailure();
        assertTrue(backoff >= 1_000L, "backoff must be >= 1s for the first failure");
        assertEquals(1, restarts.get());
        assertEquals(0, giveUps.get());
        assertFalse(p.gaveUp());
    }

    @Test
    void afterThreeFailuresTheFourthGivesUp() {
        AtomicInteger giveUps = new AtomicInteger();
        List<Long> backoffs = new ArrayList<>();
        AutoRestartPolicy p = new AutoRestartPolicy(3, 60_000L,
                backoffs::add, giveUps::incrementAndGet);
        p.recordFailure();
        p.recordFailure();
        p.recordFailure();
        long backoff = p.recordFailure();  // 4th = over the strike limit
        assertEquals(0L, backoff, "after the strike limit the policy must stop scheduling restarts");
        assertEquals(1, giveUps.get());
        assertTrue(p.gaveUp());
        assertEquals(3, backoffs.size(), "only the first 3 should produce a backoff");
    }

    @Test
    void backoffGrowsExponentially() {
        List<Long> backoffs = new ArrayList<>();
        AutoRestartPolicy p = new AutoRestartPolicy(5, 60_000L, backoffs::add, () -> {});
        p.recordFailure();
        p.recordFailure();
        p.recordFailure();
        // 1s, 2s, 4s — exactly the doubled backoff.
        assertEquals(1_000L, backoffs.get(0));
        assertEquals(2_000L, backoffs.get(1));
        assertEquals(4_000L, backoffs.get(2));
    }

    @Test
    void successResetsTheCounter() {
        AtomicInteger giveUps = new AtomicInteger();
        AutoRestartPolicy p = new AutoRestartPolicy(3, 60_000L,
                ms -> {}, giveUps::incrementAndGet);
        p.recordFailure();
        p.recordFailure();
        assertEquals(2, p.strikes());
        p.recordSuccess();
        assertEquals(0, p.strikes());
        // Now two more failures should not give up.
        p.recordFailure();
        p.recordFailure();
        assertFalse(p.gaveUp());
        assertEquals(0, giveUps.get());
    }

    @Test
    void windowExpiryResetsTheCounter() throws Exception {
        AtomicInteger giveUps = new AtomicInteger();
        AutoRestartPolicy p = new AutoRestartPolicy(2, 50L,
                ms -> {}, giveUps::incrementAndGet);
        p.recordFailure();
        Thread.sleep(80);
        p.recordFailure();
        // Only 1 strike so far because the first expired.
        assertEquals(1, p.strikes());
        assertFalse(p.gaveUp());
    }
}
