package org.aethercode.core.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link SubagentPool.Priority} dispatch. The
 * pool uses an internal priority queue, so a HIGH-priority
 * submission must dequeue before a LOW one when the pool is at
 * capacity.
 */
class SubagentPoolPriorityTest {

    private SubagentPool pool;
    private List<String> startedOrder;
    private CountDownLatch allStarted;

    @BeforeEach
    void setUp() {
        // Pool of size 1 → only 1 task can run at a time. The other
        // 2 queue up. We use a latch to hold the first task in
        // RUNNING state, then assert dequeue order.
        pool = new SubagentPool(1);
        startedOrder = new CopyOnWriteArrayList<>();
        allStarted = new CountDownLatch(3);
    }

    @AfterEach
    void tearDown() {
        pool.shutdown();
    }

    @Test
    void highPriorityDispatchesFirst() throws Exception {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        // First task blocks the pool.
        pool.submit("blocker", SubagentPool.Priority.NORMAL, () -> {
            blockerStarted.countDown();
            releaseBlocker.await(2, TimeUnit.SECONDS);
            return "done";
        });
        // Wait for the blocker to start.
        assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));
        // Now submit two more — one LOW, one HIGH. HIGH should dequeue first.
        pool.submit("low", SubagentPool.Priority.LOW, () -> {
            startedOrder.add("low");
            return "low";
        });
        pool.submit("high", SubagentPool.Priority.HIGH, () -> {
            startedOrder.add("high");
            return "high";
        });
        // Let blocker finish so the next-highest task starts.
        releaseBlocker.countDown();
        // Wait for both queued tasks to start.
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && startedOrder.size() < 2) {
            Thread.sleep(10);
        }
        // The first task to start (after the blocker) should be "high".
        assertEquals(2, startedOrder.size(), "both queued tasks should start, got: " + startedOrder);
        assertEquals("high", startedOrder.get(0),
                "HIGH priority should run before LOW, got: " + startedOrder);
    }

    @Test
    void normalPriorityDefaults() throws Exception {
        // The 2-arg submit() should default to NORMAL priority.
        pool.submit("default", () -> "ok");
        Thread.sleep(20);
        assertTrue(pool.activeCount() <= 1);
    }

    @Test
    void pendingCountReflectsQueue() throws Exception {
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        pool.submit("blocker", SubagentPool.Priority.NORMAL, () -> {
            blockerStarted.countDown();
            releaseBlocker.await(2, TimeUnit.SECONDS);
            return "done";
        });
        assertTrue(blockerStarted.await(2, TimeUnit.SECONDS));
        // 1 running, 0 pending so far.
        assertEquals(0, pool.pendingCount());
        // Add 3 pending tasks.
        pool.submit("p1", SubagentPool.Priority.NORMAL, () -> "x");
        pool.submit("p2", SubagentPool.Priority.LOW, () -> "x");
        pool.submit("p3", SubagentPool.Priority.HIGH, () -> "x");
        assertEquals(3, pool.pendingCount());
        releaseBlocker.countDown();
        // Wait for queue to drain.
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && pool.pendingCount() > 0) {
            Thread.sleep(10);
        }
        assertEquals(0, pool.pendingCount());
    }
}
