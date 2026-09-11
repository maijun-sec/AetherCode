package org.aethercode.tasks;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link CheckpointScheduler}. Verifies the
 * callback fires at the configured interval and that errors
 * are counted but don't propagate.
 */
class CheckpointSchedulerTest {

    @Test
    void tickNow_firesCallback() {
        AtomicInteger count = new AtomicInteger(0);
        CheckpointScheduler s = new CheckpointScheduler(60_000L, count::incrementAndGet);
        s.tickNow();
        assertEquals(1, count.get());
        assertEquals(1, s.invocations());
        s.tickNow();
        s.tickNow();
        assertEquals(3, count.get());
        assertEquals(3, s.invocations());
    }

    @Test
    void errorsAreCounted() {
        CheckpointScheduler s = new CheckpointScheduler(60_000L, () -> {
            throw new RuntimeException("boom");
        });
        s.tickNow();
        s.tickNow();
        assertEquals(2, s.invocations());
        assertEquals(2, s.failures(), "errors should be counted but not propagated");
    }

    @Test
    void constructor_rejectsBadArgs() {
        assertThrows(IllegalArgumentException.class,
                () -> new CheckpointScheduler(50L, () -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> new CheckpointScheduler(60_000L, (Runnable) null));
    }

    @Test
    void startStop_isIdempotent() {
        CheckpointScheduler s = new CheckpointScheduler(60_000L, () -> {});
        s.start();
        s.start(); // no-op
        s.stop();
        s.stop(); // no-op
    }
}
