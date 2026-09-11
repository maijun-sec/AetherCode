package org.aethercode.core.cost;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostBudgetTest {

    /** a clock that returns a fixed instant — no flakes from system time. */
    static class FixedClock extends Clock {
        private Instant instant;
        FixedClock(String iso) { this.instant = Instant.parse(iso); }
        void advance(java.time.Duration d) { this.instant = this.instant.plus(d); }
        @Override public Instant instant() { return instant; }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @Test
    void tryAcquire_allowsWhenUnderCap() {
        CostTracker tracker = new CostTracker().setPrice("m", 0.001, 0.002);
        CostBudget budget = new CostBudget(tracker, 1.0, 0.10);
        CostBudget.Estimate e = budget.tryAcquire("m", 100, 0); // est $0.0001
        assertTrue(e.allowed());
        assertEquals(0.0001, e.estUsd(), 1e-9);
    }

    @Test
    void tryAcquire_deniesWhenPerCallCapExceeded() {
        CostTracker tracker = new CostTracker().setPrice("m", 0.01, 0.02);
        CostBudget budget = new CostBudget(tracker, 100.0, 0.001); // per-call $0.001
        // 1000 input @ $0.01/1k = $0.01 — exceeds per-call cap
        CostBudget.Estimate e = budget.tryAcquire("m", 1000, 0);
        assertFalse(e.allowed());
    }

    @Test
    void tryAcquire_deniesWhenDailyCapWouldBeExceeded() {
        CostTracker tracker = new CostTracker().setPrice("m", 0.001, 0.001);
        CostBudget budget = new CostBudget(tracker, 0.01, 1.0);
        // 100 input @ $0.001/1k = $0.0001 — fine
        assertTrue(budget.tryAcquire("m", 100, 0).allowed());
        budget.record("m", 100, 0);
        // Now spent=0.0001, remaining=0.0099
        // Try a call that would cost 0.005 — fits
        assertTrue(budget.tryAcquire("m", 5000, 0).allowed());
        budget.record("m", 5000, 0);
        // Now spent=0.0051, remaining=0.0049
        // Try a call that would cost 0.005 — denied
        assertFalse(budget.tryAcquire("m", 5000, 0).allowed());
    }

    @Test
    void consume_throwsOnDenial() {
        CostTracker tracker = new CostTracker().setPrice("m", 0.001, 0.001);
        CostBudget budget = new CostBudget(tracker, 0.005, 1.0);
        assertThrows(BudgetExceededException.class, () -> budget.consume("m", 100_000, 0));
    }

    @Test
    void consume_recordsAfterSuccess() {
        CostTracker tracker = new CostTracker().setPrice("m", 0.001, 0.001);
        CostBudget budget = new CostBudget(tracker, 1.0, 1.0);
        budget.consume("m", 1000, 0); // $0.001
        assertEquals(0.001, budget.spentToday(), 1e-9);
        assertEquals(0.001, tracker.summary().totalCostUsd(), 1e-9);
    }

    @Test
    void record_doesNotUpdateWhenClockUnchanged() {
        CostTracker tracker = new CostTracker().setPrice("m", 0.001, 0.001);
        CostBudget budget = new CostBudget(tracker, 10.0, 10.0);
        budget.record("m", 1000, 0);
        budget.record("m", 1000, 0);
        assertEquals(0.002, budget.spentToday(), 1e-9);
    }

    @Test
    void rollover_resetsAtNextDay() {
        CostTracker tracker = new CostTracker().setPrice("m", 0.001, 0.001);
        FixedClock clock = new FixedClock("2026-08-04T10:00:00Z");
        CostBudget budget = new CostBudget(tracker, 10.0, 10.0, clock);
        budget.record("m", 1000, 0);
        assertEquals(0.001, budget.spentToday(), 1e-9);

        clock.advance(java.time.Duration.ofHours(20)); // crosses to next day
        assertEquals(0.0, budget.spentToday(), 1e-9);
    }

    @Test
    void listener_firesOnceWhenCapExceeded() {
        CostTracker tracker = new CostTracker().setPrice("m", 0.001, 0.001);
        CostBudget budget = new CostBudget(tracker, 0.005, 10.0);
        AtomicReference<String> got = new AtomicReference<>();
        budget.addListener((model, cost, cap) -> got.set(model + ":" + cost + ":" + cap));

        budget.record("m", 10_000, 0); // $0.010, exceeds $0.005 cap
        assertEquals("m:0.01:0.005", got.get());

        budget.record("m", 1000, 0); // also over, but listener should NOT re-fire
        assertEquals("m:0.01:0.005", got.get());
    }

    @Test
    void listener_resetsOnNewDay() {
        CostTracker tracker = new CostTracker().setPrice("m", 0.001, 0.001);
        FixedClock clock = new FixedClock("2026-08-04T10:00:00Z");
        CostBudget budget = new CostBudget(tracker, 0.005, 10.0, clock);
        List<String> events = new ArrayList<>();
        budget.addListener((m, c, cap) -> events.add(m + ":" + c));

        budget.record("m", 10_000, 0);
        assertEquals(1, events.size());

        clock.advance(java.time.Duration.ofHours(25));
        budget.record("m", 10_000, 0);
        assertEquals(2, events.size());
    }

    @Test
    void constructor_rejectsInvalidArgs() {
        CostTracker t = new CostTracker();
        assertThrows(IllegalArgumentException.class, () -> new CostBudget(null, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new CostBudget(t, 0));
        assertThrows(IllegalArgumentException.class, () -> new CostBudget(t, -1));
        assertThrows(IllegalArgumentException.class, () -> new CostBudget(t, 1.0, 0));
        assertThrows(IllegalArgumentException.class, () -> new CostBudget(t, 1.0, 1.0, null));
    }

    @Test
    void remainingToday_decreasesAfterRecord() {
        CostTracker tracker = new CostTracker().setPrice("m", 0.001, 0.001);
        CostBudget budget = new CostBudget(tracker, 0.01, 0.01);
        assertEquals(0.01, budget.remainingToday(), 1e-9);
        budget.record("m", 1000, 0);
        assertEquals(0.009, budget.remainingToday(), 1e-9);
    }

    @Test
    void tryAcquire_doesNotRecordItself() {
        CostTracker tracker = new CostTracker().setPrice("m", 0.001, 0.001);
        CostBudget budget = new CostBudget(tracker, 1.0, 1.0);
        CostBudget.Estimate e = budget.tryAcquire("m", 1000, 0);
        assertTrue(e.allowed());
        assertEquals(0.0, budget.spentToday(), 1e-9);
        // Now record separately
        budget.record("m", 1000, 0);
        assertEquals(0.001, budget.spentToday(), 1e-9);
    }

    @Test
    void rollover_concurrentAccessSafe() throws Exception {
        CostTracker tracker = new CostTracker().setPrice("m", 0.001, 0.001);
        FixedClock clock = new FixedClock("2026-08-04T10:00:00Z");
        CostBudget budget = new CostBudget(tracker, 1000.0, 1000.0, clock);
        int threads = 8;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try { start.await(); for (int j = 0; j < 100; j++) budget.record("m", 1, 0); }
                catch (InterruptedException ignored) {} finally { done.countDown(); }
            }).start();
        }
        start.countDown();
        done.await();
        assertEquals(threads * 100 * 0.000001, budget.spentToday(), 1e-6);
    }

    @Test
    void estimate_remainingAfterMatchesAllow() {
        CostTracker tracker = new CostTracker().setPrice("m", 0.001, 0.001);
        CostBudget budget = new CostBudget(tracker, 1.0, 1.0);
        CostBudget.Estimate e = budget.tryAcquire("m", 100_000, 0); // $0.10
        assertTrue(e.allowed());
        assertEquals(0.90, e.remainingUsdAfter(), 1e-9);
    }
}
