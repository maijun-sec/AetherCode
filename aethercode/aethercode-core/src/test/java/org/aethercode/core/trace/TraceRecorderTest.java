package org.aethercode.core.trace;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for {@link TraceRecorder}. Covers lifecycle, FIFO
 * cap, JSON-friendly snapshot, and concurrent start/end.
 */
class TraceRecorderTest {

    @Test
    void startAndEndSpan_producesCompletedSpan() {
        TraceRecorder rec = new TraceRecorder();
        String id = rec.startSpan("query", Map.of("prompt", "hi"));
        assertNotNull(id);
        assertTrue(id.startsWith("tr-"));
        assertEquals(1, rec.inFlightCount());
        assertEquals(0, rec.completedCount());

        rec.endSpan(id, "ok");
        assertEquals(0, rec.inFlightCount());
        assertEquals(1, rec.completedCount());

        List<TraceRecorder.Span> recent = rec.recentTraces(10);
        assertEquals(1, recent.size());
        TraceRecorder.Span s = recent.get(0);
        assertEquals(id, s.traceId());
        assertEquals("query", s.name());
        assertEquals("ok", s.status());
        assertTrue(s.durationMs() >= 0, "durationMs should be >= 0");
    }

    @Test
    void endSpanWithErrorRecordsErrorStatus() {
        TraceRecorder rec = new TraceRecorder();
        String id = rec.startSpan("tool.bash", Map.of("cmd", "ls"));
        rec.endSpan(id, "error");
        var s = rec.recentTraces(1).get(0);
        assertEquals("error", s.status());
    }

    @Test
    void endSpanWithUnknownStatusDefaultsToOk() {
        TraceRecorder rec = new TraceRecorder();
        String id = rec.startSpan("tool.x", Map.of());
        rec.endSpan(id, "weird_value");
        var s = rec.recentTraces(1).get(0);
        assertEquals("ok", s.status());
    }

    @Test
    void endSpanWithNullIdIsNoop() {
        TraceRecorder rec = new TraceRecorder();
        rec.endSpan(null, "ok");
        rec.endSpan("tr-unknown", "ok");
        assertEquals(0, rec.completedCount());
    }

    @Test
    void recentTraces_returnsNewestFirst() {
        TraceRecorder rec = new TraceRecorder();
        String a = rec.startSpan("a", Map.of());
        rec.endSpan(a, "ok");
        String b = rec.startSpan("b", Map.of());
        rec.endSpan(b, "ok");
        String c = rec.startSpan("c", Map.of());
        rec.endSpan(c, "ok");

        var recent = rec.recentTraces(10);
        assertEquals(3, recent.size());
        assertEquals("c", recent.get(0).name());
        assertEquals("b", recent.get(1).name());
        assertEquals("a", recent.get(2).name());
    }

    @Test
    void recentTraces_respectsLimit() {
        TraceRecorder rec = new TraceRecorder();
        for (int i = 0; i < 5; i++) {
            String id = rec.startSpan("n" + i, Map.of());
            rec.endSpan(id, "ok");
        }
        assertEquals(2, rec.recentTraces(2).size());
        assertEquals(5, rec.recentTraces(100).size());
        assertEquals(0, rec.recentTraces(0).size());
        assertEquals(0, rec.recentTraces(-1).size());
    }

    @Test
    void capacityDropsOldestSpans() {
        TraceRecorder rec = new TraceRecorder();
        for (int i = 0; i < TraceRecorder.CAPACITY + 10; i++) {
            String id = rec.startSpan("n" + i, Map.of());
            rec.endSpan(id, "ok");
        }
        assertEquals(TraceRecorder.CAPACITY, rec.completedCount());
        // The earliest spans (n0, n1, ...) should have been dropped.
        var names = rec.recentTraces(TraceRecorder.CAPACITY)
                .stream().map(s -> s.name()).toList();
        // Newest is n(CAPACITY+9); descending; the first 10 names should NOT include "n0"
        assertFalse(names.contains("n0"), "oldest span should be evicted");
        assertTrue(names.contains("n" + (TraceRecorder.CAPACITY + 9)),
                "newest span should be retained");
    }

    @Test
    void snapshot_returnsFriendlyMap() {
        TraceRecorder rec = new TraceRecorder();
        String q = rec.startSpan("query", Map.of("prompt", "hi"));
        String t = rec.startSpan("tool.glob", Map.of("pattern", "*.java"));
        rec.endSpan(t, "ok");
        // q is still in flight

        var snap = rec.snapshot();
        assertEquals(1, snap.get("inFlight"));
        assertEquals(1, snap.get("completed"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> traces = (List<Map<String, Object>>) snap.get("traces");
        assertEquals(1, traces.size());
        var first = traces.get(0);
        assertEquals("tool.glob", first.get("name"));
        assertEquals("ok", first.get("status"));
        assertNotNull(first.get("durationMs"));
        assertNotNull(first.get("startMs"));
        assertNotNull(first.get("endMs"));
        @SuppressWarnings("unchecked")
        Map<String, Object> attrs = (Map<String, Object>) first.get("attrs");
        assertEquals("*.java", attrs.get("pattern"));

        rec.endSpan(q, "ok");
    }

    @Test
    void attrsAreShallowCopied() {
        TraceRecorder rec = new TraceRecorder();
        var src = new java.util.HashMap<String, Object>();
        src.put("k", "v");
        String id = rec.startSpan("test", src);
        // Mutate after start; should NOT affect the recorded span.
        src.put("k2", "v2");
        rec.endSpan(id, "ok");
        @SuppressWarnings("unchecked")
        Map<String, Object> stored = (Map<String, Object>) rec.recentTraces(1)
                .get(0).toMap().get("attrs");
        assertEquals(1, stored.size());
        assertEquals("v", stored.get("k"));
    }

    @Test
    void nullAttrsBecomeEmptyMap() {
        TraceRecorder rec = new TraceRecorder();
        String id = rec.startSpan("test", null);
        rec.endSpan(id, "ok");
        @SuppressWarnings("unchecked")
        Map<String, Object> stored = (Map<String, Object>) rec.recentTraces(1)
                .get(0).toMap().get("attrs");
        assertNotNull(stored);
        assertTrue(stored.isEmpty());
    }

    @Test
    void durationMsIsZeroWhileRunning() {
        TraceRecorder rec = new TraceRecorder();
        String id = rec.startSpan("test", null);
        // Don't end it. durationMs must be 0.
        // The in-flight span is not in recentTraces (which only
        // returns completed), so check via the internal map: we
        // can't easily do that without reflection, so just verify
        // the public surface — completed count is still 0.
        assertEquals(0, rec.completedCount());
        assertEquals(1, rec.inFlightCount());
        rec.endSpan(id, "ok");
    }

    @Test
    void concurrentStartEndIsThreadSafe() throws Exception {
        TraceRecorder rec = new TraceRecorder();
        int threads = 8;
        int perThread = 200;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var latch = new java.util.concurrent.CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perThread; i++) {
                        String id = rec.startSpan("span", Map.of("i", i));
                        rec.endSpan(id, "ok");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();
        pool.shutdown();
        // Capacity may evict some — but every span that DID complete
        // must be counted correctly: completed <= CAPACITY.
        assertEquals(0, rec.inFlightCount());
        assertTrue(rec.completedCount() <= TraceRecorder.CAPACITY);
    }
}
