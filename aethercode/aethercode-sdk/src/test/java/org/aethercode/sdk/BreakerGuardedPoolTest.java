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

import org.aethercode.core.agent.TaskDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link BreakerGuardedPool}. Verifies that
 * the circuit breaker trips after enough failures and rejects
 * new submissions while open.
 */
class BreakerGuardedPoolTest {

    private ExecutorService pool;
    private TaskDispatcher delegate;
    private BreakerGuardedPool guarded;

    @BeforeEach
    void setUp() {
        pool = Executors.newFixedThreadPool(2);
        // A simple dispatcher backed by a real thread pool.
        delegate = new TaskDispatcher() {
            @Override public String submit(String d, Callable<?> t) { return submit(d, TaskDispatcher.Priority.NORMAL, t); }
            @Override public synchronized String submit(String d, TaskDispatcher.Priority p, Callable<?> t) {
                String id = "t-" + System.nanoTime();
                pool.submit(() -> {
                    try { t.call(); } catch (Exception e) { /* ignore */ }
                });
                return id;
            }
            @Override public boolean cancel(String id) { return false; }
            @Override public int pendingCount() { return 0; }
            @Override public int runningCount() { return 0; }
            @Override public boolean awaitIdle(long t) { return true; }
            @Override public List<String> recentTaskIds() { return List.of(); }
        };
        guarded = new BreakerGuardedPool(delegate, new CircuitBreaker(2, 60_000L));
    }

    @AfterEach
    void tearDown() throws Exception {
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    void submit_passesThroughWhenClosed() throws Exception {
        AtomicInteger ran = new AtomicInteger(0);
        String id = guarded.submit("test", () -> { ran.incrementAndGet(); return null; });
        assertNotNull(id);
        // Poll for up to 2s instead of a fixed sleep. CI load can
        // easily push a 2-thread pool past 50ms.
        long deadline = System.currentTimeMillis() + 2_000L;
        while (ran.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(1, ran.get());
    }

    @Test
    void openBreaker_rejectsSubmissions() {
        // Trip the breaker with 2 failures.
        guarded.recordFailure();
        guarded.recordFailure();
        assertTrue(guarded.breaker().isOpen());
        // New submission should be rejected.
        assertThrows(BreakerGuardedPool.BreakerOpenException.class, () ->
                guarded.submit("rejected", () -> null));
    }

    @Test
    void successfulTask_keepsBreakerClosed() {
        guarded.submit("good", () -> null);
        try { Thread.sleep(50); } catch (InterruptedException ie) {}
        // After 1 success, consecutiveFailures should be 0.
        assertFalse(guarded.breaker().isOpen());
    }
}
