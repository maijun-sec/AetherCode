package org.aethercode.tasks.channel;

import org.aethercode.tasks.channel.ResumableChannel.Decision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-P1-T08: per-connection consent ack channel with a timeout
 * that auto-denies if the user doesn't reply. The supervisor
 * blocks the tool call on {@code awaitAck(seq, timeoutMs)} and
 * the TUI / desktop completes the future with the user's
 * choice via {@code resolve(seq, decision)}.
 *
 * <p>The last test exercises the {@link ChannelRegistry} that
 * holds the per-connection channels.
 */
class ResumableChannelTest {

    private ResumableChannel.InMemory channel;
    private ExecutorService waiter;

    @BeforeEach
    void setUp() {
        channel = new ResumableChannel.InMemory("conn-1");
        waiter = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void tearDown() {
        if (channel != null) channel.close();
        if (waiter != null) waiter.shutdownNow();
    }

    @Test
    void awaitAck_completesWithResolveDecision() throws Exception {
        CompletableFuture<Decision> f = channel.awaitAck(1L, 5_000L);
        assertFalse(f.isDone());
        channel.resolve(1L, Decision.ALLOW);
        assertEquals(Decision.ALLOW, f.get(1_000L, TimeUnit.MILLISECONDS));
        assertEquals(0, channel.pendingCount());
    }

    @Test
    void awaitAck_timesOutAndAutoDenies() throws Exception {
        long start = System.currentTimeMillis();
        CompletableFuture<Decision> f = channel.awaitAck(2L, 200L);
        Decision got = f.get(1_000L, TimeUnit.MILLISECONDS);
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(Decision.DENY, got);
        // Must have waited roughly the timeout window, not zero, not 1s.
        assertTrue(elapsed >= 150L, "should wait at least ~200ms, got " + elapsed + "ms");
        assertTrue(elapsed < 800L, "should not wait 1s, got " + elapsed + "ms");
        assertEquals(0, channel.pendingCount());
        assertEquals(1, channel.timeoutCount());
    }

    @Test
    void awaitAck_sameSeqReturnsSameFuture() {
        CompletableFuture<Decision> a = channel.awaitAck(7L, 5_000L);
        CompletableFuture<Decision> b = channel.awaitAck(7L, 5_000L);
        assertSame(a, b, "duplicate await on the same seq must share the future");
        assertEquals(1, channel.pendingCount());
        // Resolving once completes both observers.
        channel.resolve(7L, Decision.ALWAYS_ALLOW);
        assertTrue(a.isDone());
        assertTrue(b.isDone());
        assertEquals(Decision.ALWAYS_ALLOW, a.join());
        assertEquals(Decision.ALWAYS_ALLOW, b.join());
    }

    @Test
    void awaitAck_rejectsZeroOrNegativeTimeout() {
        assertThrows(IllegalArgumentException.class, () -> channel.awaitAck(1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> channel.awaitAck(1L, -1L));
    }

    @Test
    void awaitAck_afterCloseImmediatelyDenies() {
        channel.close();
        CompletableFuture<Decision> f = channel.awaitAck(3L, 5_000L);
        assertTrue(f.isDone());
        assertEquals(Decision.DENY, f.join());
    }

    @Test
    void cancelAll_unblocksAllPendingAcksWithDeny() throws Exception {
        CompletableFuture<Decision> a = channel.awaitAck(10L, 60_000L);
        CompletableFuture<Decision> b = channel.awaitAck(11L, 60_000L);
        CompletableFuture<Decision> c = channel.awaitAck(12L, 60_000L);
        assertEquals(3, channel.pendingCount());
        channel.cancelAll();
        assertEquals(Decision.DENY, a.get(100L, TimeUnit.MILLISECONDS));
        assertEquals(Decision.DENY, b.get(100L, TimeUnit.MILLISECONDS));
        assertEquals(Decision.DENY, c.get(100L, TimeUnit.MILLISECONDS));
        assertEquals(0, channel.pendingCount());
    }

    // -- ChannelRegistry --------------------------------------------------

    @Test
    void registry_getOrCreateReturnsSameInstanceAndRemoveCancelsPending() throws Exception {
        try (ChannelRegistry reg = new ChannelRegistry()) {
            ResumableChannel first = reg.getOrCreate("conn-A");
            ResumableChannel second = reg.getOrCreate("conn-A");
            assertSame(first, second, "getOrCreate is idempotent for the same id");
            ResumableChannel third = reg.getOrCreate("conn-B");
            assertEquals(2, reg.size());
            assertEquals(2, reg.createdCount());

            // Cancel all on conn-A; pending acks should unblock.
            CompletableFuture<Decision> f = first.awaitAck(99L, 60_000L);
            assertTrue(reg.remove("conn-A"));
            assertEquals(Decision.DENY, f.get(100L, TimeUnit.MILLISECONDS));
            assertEquals(1, reg.size());
            // remove on unknown id is a no-op and returns false.
            assertFalse(reg.remove("conn-A"));
            // third is still alive.
            assertSame(third, reg.find("conn-B").orElse(null));
        }
    }
}
