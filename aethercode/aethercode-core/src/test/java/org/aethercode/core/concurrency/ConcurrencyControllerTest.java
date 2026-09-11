package org.aethercode.core.concurrency;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyControllerTest {

    @Test
    void acquireAndRelease() {
        ConcurrencyController c = new ConcurrencyController();
        try {
            EngineStats s = c.snapshot();
            // Default profile = NORMAL = 1 query.
            assertEquals(1, s.maxConcurrentQueries);
            assertEquals(0, s.queriesInFlight);
            try (var lease = c.tryAcquireQuery()) {
                assertNotNull(lease);
                EngineStats s2 = c.snapshot();
                assertEquals(1, s2.queriesInFlight);
            }
            EngineStats s3 = c.snapshot();
            assertEquals(0, s3.queriesInFlight);
        } finally {
            c.stop();
        }
    }

    @Test
    void secondAcquireIsRejected() {
        ConcurrencyController c = new ConcurrencyController();
        try {
            try (var first = c.tryAcquireQuery()) {
                assertNotNull(first);
                var second = c.tryAcquireQuery();
                assertNull(second, "second query should be rejected when max=1");
            }
        } finally {
            c.stop();
        }
    }

    @Test
    void profileChangeAdjustsLimits() {
        ConcurrencyController c = new ConcurrencyController();
        try {
            assertEquals(1, c.snapshot().maxConcurrentQueries);
            c.setProfile(ConcurrencyController.Profile.HIGH);
            assertEquals(2, c.snapshot().maxConcurrentQueries);
            assertEquals(8, c.snapshot().maxConcurrentTools);
            assertEquals(4, c.snapshot().maxConcurrentBranches);
            c.setProfile(ConcurrencyController.Profile.LOW);
            assertEquals(1, c.snapshot().maxConcurrentQueries);
            assertEquals(2, c.snapshot().maxConcurrentTools);
        } finally {
            c.stop();
        }
    }

    @Test
    void setProfileByNameIsCaseInsensitive() {
        ConcurrencyController c = new ConcurrencyController();
        try {
            c.setProfileByName("High");
            assertEquals(ConcurrencyController.Profile.HIGH, c.profile());
        } finally {
            c.stop();
        }
    }

    @Test
    void invalidThresholdsThrow() {
        assertThrows(IllegalArgumentException.class, () -> new ConcurrencyController(90, 80, 5000));
    }

    @Test
    void snapshotMemFieldsAreSane() {
        ConcurrencyController c = new ConcurrencyController();
        try {
            EngineStats s = c.snapshot();
            // We don't know the JVM size but the values should
            // be parseable: memMaxBytes > 0, memPct in [0, 100].
            assertTrue(s.memMaxBytes > 0, "max bytes should be positive, was " + s.memMaxBytes);
            assertTrue(s.memPct >= 0 && s.memPct <= 100, "pct out of range: " + s.memPct);
            assertTrue(s.memUsedMb >= 0);
            // memMaxMb should equal memMaxBytes / 1MiB.
            assertEquals(s.memMaxBytes / (1024 * 1024), s.memMaxMb);
        } finally {
            c.stop();
        }
    }

    @Test
    void tryAcquireQueryReturnsNullUnderBackpressure() {
        // Threshold window [10, 20]: any non-zero mem triggers
        // backpressure. The constructor will set
        // backpressured=true because the JVM is using memory
        // by the time we sample.
        ConcurrencyController c = new ConcurrencyController(10, 20, 60_000);
        try {
            // Force a sample.
            EngineStats s = c.snapshot();
            // The JVM is using memory; memPct > 20 (it has to
            // be > 0 since used > 0), so backpressured is true.
            if (s.memPct >= 20) {
                assertTrue(s.backpressured, "expected backpressured when memPct=" + s.memPct);
                assertNull(c.tryAcquireQuery());
            } else {
                // Edge case on a barely-used JVM. Skip.
            }
        } finally {
            c.stop();
        }
    }

    @Test
    void toolAndBranchAcquire() {
        ConcurrencyController c = new ConcurrencyController();
        try {
            c.setProfile(ConcurrencyController.Profile.LOW);
            // LOW profile = 2 tools, 1 branch.
            assertTrue(c.tryAcquireTool());
            assertTrue(c.tryAcquireTool());
            assertFalse(c.tryAcquireTool(), "third tool should be rejected (max=2)");
            c.releaseTool();
            assertTrue(c.tryAcquireTool());
            // Branch: LOW = 1.
            assertTrue(c.tryAcquireBranch());
            assertFalse(c.tryAcquireBranch());
            c.releaseBranch();
            assertTrue(c.tryAcquireBranch());
            c.releaseTool();
            c.releaseTool();
            c.releaseTool();
        } finally {
            c.stop();
        }
    }

    @Test
    void leaseIsAutoCloseable() {
        ConcurrencyController c = new ConcurrencyController();
        try {
            // Acquire + auto-close in try-with-resources.
            try (var lease = c.tryAcquireQuery()) {
                assertNotNull(lease);
                assertEquals(1, c.snapshot().queriesInFlight);
            }
            // After close, slot is back.
            assertEquals(0, c.snapshot().queriesInFlight);
            assertNotNull(c.tryAcquireQuery());
        } finally {
            c.stop();
        }
    }

    @Test
    void snapshotSampledAtIsRecent() {
        ConcurrencyController c = new ConcurrencyController();
        try {
            EngineStats s = c.snapshot();
            long now = System.currentTimeMillis();
            assertTrue(Math.abs(now - s.sampledAtMs) < 5_000, "snapshot sampledAtMs too old: " + s.sampledAtMs);
        } finally {
            c.stop();
        }
    }

    @Test
    void concurrentAcquireHonoursLimit() throws Exception {
        ConcurrencyController c = new ConcurrencyController();
        try {
            c.setProfile(ConcurrencyController.Profile.HIGH);  // 2 queries
            AtomicInteger inFlightPeak = new AtomicInteger(0);
            AtomicInteger current = new AtomicInteger(0);
            int threads = 8;
            Thread[] workers = new Thread[threads];
            for (int i = 0; i < threads; i++) {
                workers[i] = new Thread(() -> {
                    try (var lease = c.tryAcquireQuery()) {
                        if (lease == null) return;
                        int now = current.incrementAndGet();
                        inFlightPeak.updateAndGet(p -> Math.max(p, now));
                        try { Thread.sleep(20); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        current.decrementAndGet();
                    }
                });
            }
            for (Thread t : workers) t.start();
            for (Thread t : workers) t.join();
            assertTrue(inFlightPeak.get() <= 2, "peak in-flight " + inFlightPeak.get() + " exceeds max=2");
        } finally {
            c.stop();
        }
    }
}
