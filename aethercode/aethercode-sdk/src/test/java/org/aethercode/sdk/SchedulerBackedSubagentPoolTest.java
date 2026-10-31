/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.sdk;

import org.aethercode.tasks.TaskScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link SchedulerBackedSubagentPool}. The
 * facade wraps a TaskScheduler and exposes a SubagentPool-like
 * API.
 */
class SchedulerBackedSubagentPoolTest {

    private TaskScheduler scheduler;
    private SchedulerBackedSubagentPool pool;

    @BeforeEach
    void setUp() {
        scheduler = new TaskScheduler(2, ev -> {});
        pool = new SchedulerBackedSubagentPool(scheduler);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) pool.shutdown();
        if (scheduler != null) scheduler.close();
    }

    @Test
    void submit_runsTaskAndIncrementsCompleted() throws Exception {
        AtomicInteger ran = new AtomicInteger(0);
        String id = pool.submit("test", () -> ran.incrementAndGet());
        assertTrue(pool.awaitIdle(2_000));
        assertEquals(1, ran.get());
        assertEquals(1L, pool.completedCount());
    }

    @Test
    void submitWithPriority_dispatches() throws Exception {
        AtomicInteger highCount = new AtomicInteger(0);
        AtomicInteger lowCount = new AtomicInteger(0);
        // Submit a slow blocker so the next two queue up.
        CountDownLatch blocker = new CountDownLatch(1);
        pool.submit("blocker", () -> {
            blocker.await(2, TimeUnit.SECONDS);
            return null;
        });
        // Sleep a bit so the blocker is running.
        Thread.sleep(30);
        pool.submit("low", TaskScheduler.Priority.LOW, lowCount::incrementAndGet);
        pool.submit("high", TaskScheduler.Priority.HIGH, highCount::incrementAndGet);
        blocker.countDown();
        assertTrue(pool.awaitIdle(2_000));
        // Both ran, but high should have run before low in the queue.
        assertEquals(1, highCount.get());
        assertEquals(1, lowCount.get());
    }

    @Test
    void cancel_pendingTaskDoesNotRun() throws Exception {
        // Saturate both workers with blockers so the doomed task is
        // guaranteed to queue (the previous version used only one
        // blocker + a 20ms sleep, which was flaky when the scheduler
        // managed to pick up the doomed task before cancel).
        CountDownLatch blocker1 = new CountDownLatch(1);
        CountDownLatch blocker2 = new CountDownLatch(1);
        pool.submit("blocker1", () -> { blocker1.await(2, TimeUnit.SECONDS); return null; });
        pool.submit("blocker2", () -> { blocker2.await(2, TimeUnit.SECONDS); return null; });
        // Give the scheduler time to start both blockers.
        Thread.sleep(50);
        assertEquals(2, pool.runningCount(), "both blockers should be running");
        AtomicInteger ran = new AtomicInteger(0);
        String doomed = pool.submit("doomed", () -> ran.incrementAndGet());
        assertEquals(1, pool.pendingCount(), "doomed should be queued");
        assertTrue(pool.cancel(doomed));
        // Release the blockers so the scheduler can drain.
        blocker1.countDown();
        blocker2.countDown();
        assertTrue(pool.awaitIdle(2_000));
        assertEquals(0, ran.get(), "cancelled task must not have run");
    }
}
