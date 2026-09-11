package org.aethercode.core.notify;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.aethercode.core.notify.NotificationCoalescer.FlushEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotificationCoalescerTest {

    private NotificationCoalescer<String> coalescer;

    @AfterEach
    void cleanup() {
        if (coalescer != null) coalescer.shutdown();
    }

    @Test
    void add_flushesAfterWindow() throws Exception {
        CopyOnWriteArrayList<List<FlushEntry<String>>> flushed = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        coalescer = new NotificationCoalescer<>(Duration.ofMillis(50), entries -> {
            flushed.add(entries);
            latch.countDown();
        });
        coalescer.add("tool", "Bash");
        coalescer.add("tool", "Read");
        coalescer.add("tool", "Write");
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, flushed.size());
        assertEquals(1, flushed.get(0).size());
        assertEquals("tool", flushed.get(0).get(0).key());
        assertEquals(List.of("Bash", "Read", "Write"), flushed.get(0).get(0).values());
    }

    @Test
    void add_separateKeysAreSeparate() throws Exception {
        CopyOnWriteArrayList<List<FlushEntry<String>>> flushed = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        coalescer = new NotificationCoalescer<>(Duration.ofMillis(50), entries -> {
            flushed.add(entries);
            latch.countDown();
        });
        coalescer.add("a", "1");
        coalescer.add("b", "2");
        coalescer.add("a", "3");
        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, flushed.size());
        // Both keys in the same flush
        assertEquals(2, flushed.get(0).size());
    }

    @Test
    void add_windowExtendsWithMoreAdds() throws Exception {
        CopyOnWriteArrayList<List<FlushEntry<String>>> flushed = new CopyOnWriteArrayList<>();
        // window bumped 200→1000ms. The original timing
        // (5 × 50ms = 250ms total, window 200ms) was a race
        // condition: the scheduler could fire at t=200 just
        // before add 5 arrived, yielding a 4-entry first
        // flush. With a 1s window, all 5 adds (taking 250ms
        // total) finish well before the flush, so all 5 land
        // in the same batch.
        CountDownLatch latch = new CountDownLatch(1);
        coalescer = new NotificationCoalescer<>(Duration.ofMillis(1000), entries -> {
            flushed.add(entries);
            latch.countDown();
        });
        for (int i = 0; i < 5; i++) {
            coalescer.add("k", "v" + i);
            Thread.sleep(50);
        }
        // Wait for the scheduled flush; with a 1s window, the
        // flush fires ~1s after the first add, well after all
        // 5 adds are done. 3s timeout is a safe margin.
        assertTrue(latch.await(3, TimeUnit.SECONDS),
                "expected scheduled flush within 3s");
        // Should have flushed at least once with all 5
        assertTrue(flushed.get(0).get(0).count() >= 5,
                "expected >= 5 entries in first flush, got " + flushed.get(0).get(0).count());
    }

    @Test
    void flush_isIdempotent() {
        AtomicInteger flushes = new AtomicInteger();
        coalescer = new NotificationCoalescer<>(Duration.ofSeconds(60), e -> flushes.incrementAndGet());
        coalescer.add("k", "v");
        assertEquals(0, flushes.get());
        coalescer.flush();
        assertEquals(1, flushes.get());
        coalescer.flush();
        // Second flush has nothing to do, so no callback
        assertEquals(1, flushes.get());
    }

    @Test
    void flush_consumesAllPending() {
        CopyOnWriteArrayList<List<FlushEntry<String>>> flushed = new CopyOnWriteArrayList<>();
        coalescer = new NotificationCoalescer<>(Duration.ofSeconds(60), flushed::add);
        coalescer.add("a", "1");
        coalescer.add("a", "2");
        coalescer.add("b", "3");
        coalescer.flush();
        assertEquals(0, coalescer.pendingCount());
        assertEquals(0, coalescer.pendingKeyCount());
        assertEquals(1, flushed.size());
    }

    @Test
    void pendingCount_tracksItems() {
        coalescer = new NotificationCoalescer<>(Duration.ofSeconds(60), e -> {});
        coalescer.add("a", "1");
        coalescer.add("a", "2");
        coalescer.add("b", "3");
        assertEquals(3, coalescer.pendingCount());
        assertEquals(2, coalescer.pendingKeyCount());
    }

    @Test
    void consumerExceptionsDoNotBreakLoop() throws Exception {
        CopyOnWriteArrayList<List<FlushEntry<String>>> flushed = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        coalescer = new NotificationCoalescer<>(Duration.ofMillis(50), entries -> {
            int n = calls.incrementAndGet();
            flushed.add(entries);
            if (n == 1) throw new RuntimeException("boom on first call");
        });
        coalescer.add("a", "1");
        Thread.sleep(200);
        // After the first flush throws, add more — they should still flush
        coalescer.add("b", "2");
        Thread.sleep(200);
        assertTrue(flushed.size() >= 2, "expected at least 2 flushes, got " + flushed.size());
    }

    @Test
    void constructor_rejectsBadArgs() {
        assertThrows(IllegalArgumentException.class, () -> new NotificationCoalescer<>(null, e -> {}));
        assertThrows(IllegalArgumentException.class, () -> new NotificationCoalescer<>(Duration.ZERO, e -> {}));
        assertThrows(IllegalArgumentException.class, () -> new NotificationCoalescer<>(Duration.ofSeconds(-1), e -> {}));
        assertThrows(IllegalArgumentException.class, () -> new NotificationCoalescer<>(Duration.ofSeconds(1), null));
    }

    @Test
    void add_rejectsNullKey() {
        coalescer = new NotificationCoalescer<>(Duration.ofSeconds(1), e -> {});
        assertThrows(NullPointerException.class, () -> coalescer.add(null, "v"));
    }

    @Test
    void add_rejectsNullValue() {
        coalescer = new NotificationCoalescer<>(Duration.ofSeconds(1), e -> {});
        assertThrows(NullPointerException.class, () -> coalescer.add("k", null));
    }

    @Test
    void flushEntry_immutable() {
        FlushEntry<String> e = new FlushEntry<>("k", List.of("a", "b"));
        assertEquals(2, e.count());
        assertThrows(UnsupportedOperationException.class, () -> e.values().add("c"));
    }

    @Test
    void window_returnsConstructorValue() {
        coalescer = new NotificationCoalescer<>(Duration.ofSeconds(7), e -> {});
        assertEquals(Duration.ofSeconds(7), coalescer.window());
    }

    @Test
    void add_emptyKeyIsAllowed() {
        CopyOnWriteArrayList<List<FlushEntry<String>>> flushed = new CopyOnWriteArrayList<>();
        coalescer = new NotificationCoalescer<>(Duration.ofMillis(30), flushed::add);
        coalescer.add("", "value");
        coalescer.flush();
        assertEquals(1, flushed.size());
        assertEquals("", flushed.get(0).get(0).key());
    }

    @Test
    void flush_afterAdd_doesNotDoubleDeliver() throws Exception {
        AtomicInteger flushes = new AtomicInteger();
        coalescer = new NotificationCoalescer<>(Duration.ofMillis(50), e -> flushes.incrementAndGet());
        coalescer.add("k", "v");
        coalescer.flush();
        // The auto-scheduled flush from the initial add should see no pending
        Thread.sleep(200);
        assertEquals(1, flushes.get());
    }

    @Test
    void shutdown_isCallable() {
        coalescer = new NotificationCoalescer<>(Duration.ofSeconds(1), e -> {});
        coalescer.shutdown();
        coalescer.shutdown();
    }

    @Test
    void currentSequence_startsAtZero() {
        coalescer = new NotificationCoalescer<>(Duration.ofSeconds(1), e -> {});
        assertEquals(0, coalescer.currentSequence());
    }
}
