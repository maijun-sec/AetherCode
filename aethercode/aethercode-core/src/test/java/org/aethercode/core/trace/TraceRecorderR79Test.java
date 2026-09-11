package org.aethercode.core.trace;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for parent linkage + getTrace. The R78 tests
 * (start/end/snapshot/concurrent) live in {@code TraceRecorderTest}
 * and are unchanged.
 */
class TraceRecorderR79Test {

    @Test
    void startChildSpan_recordsParentId() {
        TraceRecorder rec = new TraceRecorder();
        String rootId = rec.startSpan("query", Map.of("runId", "run-1"));
        String childId = rec.startChildSpan(rootId, "tool.glob", Map.of("pattern", "*.java"));
        // End both; then inspect from the completed deque.
        rec.endSpan(childId, "ok");
        rec.endSpan(rootId, "ok");
        var spans = rec.recentTraces(10);
        TraceRecorder.Span r = null, c = null;
        for (var s : spans) {
            if (s.traceId().equals(rootId)) r = s;
            if (s.traceId().equals(childId)) c = s;
        }
        assertNotNull(r);
        assertNotNull(c);
        assertNull(r.parentSpanId());
        assertEquals(rootId, c.parentSpanId());
        assertTrue(r.isRoot());
        assertFalse(c.isRoot());
    }

    @Test
    void startSpanIsBackwardsCompatible_rootHasNoParent() {
        // R78 callers (startSpan) should still produce root spans.
        TraceRecorder rec = new TraceRecorder();
        String id = rec.startSpan("query", Map.of());
        rec.endSpan(id, "ok");
        var s = rec.recentTraces(1).get(0);
        assertNull(s.parentSpanId());
        assertTrue(s.isRoot());
    }

    @Test
    void getTrace_returnsRootAndChildren() {
        TraceRecorder rec = new TraceRecorder();
        String rootId = rec.startSpan("query", Map.of());
        String aId = rec.startChildSpan(rootId, "tool.glob", Map.of());
        String bId = rec.startChildSpan(rootId, "tool.bash", Map.of());
        // A grandchild (tool.bash sub-step) — not used by the
        // current query loop but exercises the deeper tree path.
        String gcId = rec.startChildSpan(bId, "tool.bash.inner", Map.of());
        rec.endSpan(aId, "ok");
        rec.endSpan(gcId, "ok");
        rec.endSpan(bId, "ok");
        rec.endSpan(rootId, "ok");

        List<TraceRecorder.Span> trace = rec.getTrace(rootId);
        // The returned list contains the root + every span whose
        // parent chain leads back to it. We don't enforce a
        // specific child order, but the root is always first.
        assertEquals(rootId, trace.get(0).traceId());
        // 4 total: root + 2 children + 1 grandchild.
        assertEquals(4, trace.size());
        // Confirm parent linkage in the returned list.
        for (var s : trace) {
            if (s.traceId().equals(rootId)) assertNull(s.parentSpanId());
            else if (s.traceId().equals(aId)) assertEquals(rootId, s.parentSpanId());
            else if (s.traceId().equals(bId)) assertEquals(rootId, s.parentSpanId());
            else if (s.traceId().equals(gcId)) assertEquals(bId, s.parentSpanId());
            else throw new AssertionError("unexpected span in trace: " + s.traceId());
        }
    }

    @Test
    void getTrace_unknownIdReturnsEmpty() {
        TraceRecorder rec = new TraceRecorder();
        // No spans started.
        assertTrue(rec.getTrace("tr-unknown").isEmpty());
        // Start something else — still empty for unknown id.
        String id = rec.startSpan("query", Map.of());
        rec.endSpan(id, "ok");
        assertTrue(rec.getTrace("tr-other").isEmpty());
        // Null / empty are also empty.
        assertTrue(rec.getTrace(null).isEmpty());
        assertTrue(rec.getTrace("").isEmpty());
    }

    @Test
    void getTrace_orphanChildWithoutKnownRoot() {
        // If the root has been evicted but a child still has
        // parentSpanId pointing to it, getTrace(orphanRoot) returns
        // the surviving children. Useful for partial-eviction cases.
        TraceRecorder rec = new TraceRecorder();
        // Fill the deque with garbage so the root gets evicted.
        for (int i = 0; i < TraceRecorder.CAPACITY; i++) {
            String id = rec.startSpan("filler-" + i, Map.of());
            rec.endSpan(id, "ok");
        }
        String rootId = rec.startSpan("query", Map.of());
        String childId = rec.startChildSpan(rootId, "tool.glob", Map.of());
        // End the child, evict the root.
        rec.endSpan(childId, "ok");
        rec.endSpan(rootId, "ok");
        // Now the child is the newest entry; root may or may not
        // have survived the eviction. We just check that the
        // child is part of the trace by its parent linkage.
        var trace = rec.getTrace(rootId);
        boolean foundChild = trace.stream().anyMatch(s -> s.traceId().equals(childId));
        assertTrue(foundChild, "child should be reachable via its parent id");
    }

    @Test
    void getTrace_inFlightRootNotPresentButDescendantsReturned() {
        // The TUI only sees completed spans. A root that's still
        // running is not in the completed deque, but its already-
        // completed descendants ARE — and they still link back to
        // the missing root via parentSpanId. The BFS in getTrace
        // finds them by their parentId alone.
        TraceRecorder rec = new TraceRecorder();
        String rootId = rec.startSpan("query", Map.of());
        String childId = rec.startChildSpan(rootId, "tool.glob", Map.of());
        rec.endSpan(childId, "ok");
        // Don't end root — it's still in-flight.
        var trace = rec.getTrace(rootId);
        // 1 entry: the child (whose parentSpanId still points
        // at the missing root). The root itself isn't in the
        // completed deque.
        assertEquals(1, trace.size());
        assertEquals(childId, trace.get(0).traceId());
        assertEquals(rootId, trace.get(0).parentSpanId());
        rec.endSpan(rootId, "ok");
        // Now both are completed → 2 entries (root first).
        var trace2 = rec.getTrace(rootId);
        assertEquals(2, trace2.size());
        assertEquals(rootId, trace2.get(0).traceId());
    }

    @Test
    void spanToMap_includesParentSpanId() {
        TraceRecorder rec = new TraceRecorder();
        String rootId = rec.startSpan("query", Map.of());
        String childId = rec.startChildSpan(rootId, "tool.x", Map.of());
        rec.endSpan(childId, "ok");
        rec.endSpan(rootId, "ok");
        // Find the child in the toMap output.
        var snap = rec.snapshot(10);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> traces = (List<Map<String, Object>>) snap.get("traces");
        Map<String, Object> childMap = null;
        Map<String, Object> rootMap = null;
        for (var t : traces) {
            if (childId.equals(t.get("traceId"))) childMap = t;
            if (rootId.equals(t.get("traceId"))) rootMap = t;
        }
        assertNotNull(childMap);
        assertNotNull(rootMap);
        assertTrue(childMap.containsKey("parentSpanId"));
        assertTrue(rootMap.containsKey("parentSpanId"));
        assertEquals(rootId, childMap.get("parentSpanId"));
        assertNull(rootMap.get("parentSpanId"));
    }

    @Test
    void snapshotForTrace_returnsTraceIdAndSpans() {
        TraceRecorder rec = new TraceRecorder();
        String rootId = rec.startSpan("query", Map.of());
        String aId = rec.startChildSpan(rootId, "tool.glob", Map.of());
        rec.endSpan(aId, "ok");
        rec.endSpan(rootId, "ok");

        var snap = rec.snapshotForTrace(rootId);
        assertEquals(rootId, snap.get("traceId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spans = (List<Map<String, Object>>) snap.get("spans");
        assertEquals(2, spans.size());
        // Header fields still report the whole recorder.
        assertEquals(0, ((Number) snap.get("inFlight")).intValue());
        assertEquals(2, ((Number) snap.get("completed")).intValue());
    }

    @Test
    void snapshotForTrace_unknownIdReturnsEmptySpans() {
        TraceRecorder rec = new TraceRecorder();
        var snap = rec.snapshotForTrace("tr-nope");
        assertEquals("tr-nope", snap.get("traceId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> spans = (List<Map<String, Object>>) snap.get("spans");
        assertTrue(spans.isEmpty());
    }
}
