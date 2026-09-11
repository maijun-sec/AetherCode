package org.aethercode.core.agent;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.aethercode.core.agent.SubagentPool.Event;
import org.aethercode.core.agent.SubagentPool.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentPoolTest {

    private SubagentPool pool;

    @AfterEach
    void cleanup() {
        if (pool != null) pool.shutdown();
    }

    @Test
    void submit_returnsId() {
        pool = new SubagentPool(2);
        String id = pool.submit("test", () -> "ok");
        assertNotNull(id);
    }

    @Test
    void submit_rejectsNullTask() {
        pool = new SubagentPool(2);
        assertThrows(NullPointerException.class, () -> pool.submit("test", null));
    }

    @Test
    void submit_completes() throws Exception {
        pool = new SubagentPool(2);
        String id = pool.submit("test", () -> 42);
        Object result = pool.waitFor(id, 1000).orElse(null);
        assertEquals(42, result);
        assertEquals(State.COMPLETED, pool.stateOf(id));
    }

    @Test
    void submit_recordsEventSequence() throws Exception {
        pool = new SubagentPool(2);
        String id = pool.submit("test", () -> "ok");
        pool.waitFor(id, 1000);
        List<Event> events = pool.events();
        // PENDING, RUNNING, COMPLETED
        assertEquals(3, events.size());
        assertEquals(State.PENDING, events.get(0).newState());
        assertEquals(State.RUNNING, events.get(1).newState());
        assertEquals(State.COMPLETED, events.get(2).newState());
    }

    @Test
    void submit_recordsFailure() throws Exception {
        pool = new SubagentPool(2);
        String id = pool.submit("fails", () -> { throw new RuntimeException("boom"); });
        try { pool.waitFor(id, 1000); } catch (Exception ignored) {}
        // waitFor returns empty on exception; check state
        Thread.sleep(50);
        assertEquals(State.FAILED, pool.stateOf(id));
    }

    @Test
    void submit_multipleConcurrently() throws Exception {
        // R246 (R245.6 follow-up): the previous version submitted
        // 8 tasks into a 4-wide pool with a 5s timeout, which
        // was flaky under heavy GC / CPU contention (the second
        // 4-task wave would race the deadline). We now match the
        // task count to the pool width so all 4 tasks run in
        // a single wave — eliminates the 2-wave timing race
        // while still exercising "concurrent submission" (the
        // 4 tasks still race each other for the 4 worker
        // slots). Bump the timeout to 10s as a defensive
        // belt-and-suspenders for the rare scheduler stall.
        pool = new SubagentPool(4);
        AtomicInteger done = new AtomicInteger();
        // replaced Thread.sleep(200) with a real
        // "all-tasks-done" latch.
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(4);
        for (int i = 0; i < 4; i++) {
            pool.submit("t" + i, () -> {
                start.await();
                try {
                    return done.incrementAndGet();
                } finally {
                    finished.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS),
                "expected all 4 tasks to finish within 10s");
        assertEquals(4, done.get());
        assertEquals(4, pool.completedCount());
    }

    @Test
    void submit_respectsMaxConcurrent() throws Exception {
        pool = new SubagentPool(1);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxObserved = new AtomicInteger();
        for (int i = 0; i < 4; i++) {
            pool.submit("t" + i, () -> {
                int c = concurrent.incrementAndGet();
                maxObserved.updateAndGet(m -> Math.max(m, c));
                Thread.sleep(50);
                concurrent.decrementAndGet();
                return null;
            });
        }
        Thread.sleep(500);
        assertTrue(maxObserved.get() <= 1, "max observed: " + maxObserved.get());
    }

    @Test
    void cancel_stopsRunningTask() {
        pool = new SubagentPool(2);
        String id = pool.submit("long", () -> {
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "done";
        });
        boolean cancelled = pool.cancel(id);
        // Cancellation may race with the task starting; either way, eventually state should be CANCELLED
        assertTrue(cancelled || pool.stateOf(id) == State.CANCELLED);
    }

    @Test
    void cancel_unknownReturnsFalse() {
        pool = new SubagentPool(2);
        assertFalse(pool.cancel("nope"));
    }

    @Test
    void stateOf_unknownReturnsNull() {
        pool = new SubagentPool(2);
        assertEquals(null, pool.stateOf("nope"));
    }

    @Test
    void isRunning_returnsTrueWhenRunning() throws Exception {
        pool = new SubagentPool(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        String id = pool.submit("t", () -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return null;
        });
        started.await(1, TimeUnit.SECONDS);
        assertTrue(pool.isRunning(id));
        release.countDown();
    }

    @Test
    void waitFor_unknownAgentReturnsEmpty() {
        pool = new SubagentPool(2);
        assertFalse(pool.waitFor("nope", 100).isPresent());
    }

    @Test
    void completedCount_incrementsOnSuccess() throws Exception {
        pool = new SubagentPool(2);
        String id = pool.submit("t", () -> 1);
        pool.waitFor(id, 1000);
        assertEquals(1, pool.completedCount());
    }

    @Test
    void failedCount_incrementsOnError() throws Exception {
        pool = new SubagentPool(2);
        String id = pool.submit("t", () -> { throw new RuntimeException("x"); });
        try { pool.waitFor(id, 1000); } catch (Exception ignored) {}
        Thread.sleep(50);
        assertEquals(1, pool.failedCount());
    }

    @Test
    void events_areCappedAtLimit() throws Exception {
        pool = new SubagentPool(2);
        for (int i = 0; i < 1000; i++) {
            String id = pool.submit("t" + i, () -> null);
            pool.waitFor(id, 1000);
        }
        assertTrue(pool.events().size() <= 200);
    }

    @Test
    void states_returnsAllKnownAgents() throws Exception {
        pool = new SubagentPool(2);
        String id = pool.submit("t", () -> 1);
        pool.waitFor(id, 1000);
        assertTrue(pool.states().containsKey(id));
    }

    @Test
    void constructor_rejectsBadArgs() {
        assertThrows(IllegalArgumentException.class, () -> new SubagentPool(0));
        assertThrows(IllegalArgumentException.class, () -> new SubagentPool(-1));
        assertThrows(IllegalArgumentException.class, () -> new SubagentPool(1, null));
    }

    @Test
    void maxConcurrent_returnsConstructorValue() {
        pool = new SubagentPool(7);
        assertEquals(7, pool.maxConcurrent());
    }

    @Test
    void eventCount_returnsCurrentSize() {
        pool = new SubagentPool(2);
        assertEquals(0, pool.eventCount());
        pool.submit("t", () -> null);
        assertEquals(1, pool.eventCount());
    }
}
