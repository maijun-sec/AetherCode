package org.aethercode.core.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * lightweight in-memory span recorder.
 * adds parent linkage (parentSpanId), child-span API
 *      ({@link #startChildSpan}), and {@link #getTrace} to fetch
 *      a root span + all of its descendants.
 *
 * <p>This is intentionally NOT a full OpenTelemetry implementation —
 * we don't have a propagation context, exporters, or sampling. We
 * record spans with optional parent linkage, and let the TUI
 * render a tree when it asks for one. The recording shape is:
 * <ul>
 *   <li>Root span: {@code "query"} (one per user turn), no parent.</li>
 *   <li>Child spans: {@code "tool.<name>"} (one per tool call),
 *       with the root span's traceId as {@code parentSpanId}.</li>
 * </ul>
 *
 * <p>Scope: per-engine. The recorder is created in
 * {@code AetherCodeEngine}'s constructor and never reset. Spans
 * are kept in a bounded deque (capacity 256); older spans are
 * silently dropped when the cap is exceeded. The in-flight map
 * holds spans that have started but not yet ended — used to show
 * the user that a query is still in progress.
 */
public final class TraceRecorder {

    /** Maximum number of completed spans kept in memory. Older spans
     *  are dropped on overflow. Sized to keep the snapshot cheap
     *  for the TUI but retain enough history to be useful. */
    public static final int CAPACITY = 256;

    /** A single span. {@code endMs} is 0 while the span is still
     *  in flight; status is one of {@code "running" / "ok" / "error"}.
     *  {@code parentSpanId} is null for root spans and the parent's
     *  traceId for children. {@code attrs} is an open map of
     *  key/value annotations the caller chose to attach (e.g. tool
     *  name, model id, etc.). */
    public record Span(
            String traceId,
            String name,
            String parentSpanId,
            long startMs,
            long endMs,
            String status,
            Map<String, Object> attrs) {

        /** Duration in milliseconds; 0 if the span is still running. */
        public long durationMs() {
            return endMs > 0 ? endMs - startMs : 0L;
        }

        /** True if this span has no parent (i.e. a root). */
        public boolean isRoot() {
            return parentSpanId == null;
        }

        /** Convert the span to a JSON-friendly map. The
         *  {@code durationMs} is included so the TUI can render it
         *  without recomputing. {@code parentSpanId} is always
         *  present (null for roots) so the consumer doesn't have
         *  to special-case the field name. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("traceId",     traceId);
            m.put("name",        name);
            m.put("parentSpanId", parentSpanId);
            m.put("startMs",     startMs);
            m.put("endMs",       endMs);
            m.put("status",      status);
            m.put("durationMs",  durationMs());
            m.put("attrs",       attrs == null ? Map.of() : attrs);
            return m;
        }
    }

    /** In-flight spans keyed by traceId. Cleared when the span is
     *  ended. */
    private final Map<String, Span> inFlight = new ConcurrentHashMap<>();

    /** Completed spans in insertion order (oldest first). Bounded
     *  to {@link #CAPACITY}; oldest is dropped on overflow. */
    private final ConcurrentLinkedDeque<Span> completed = new ConcurrentLinkedDeque<>();

    /** Begin a new root span (no parent). Returns the generated
     *  traceId which must be passed to {@link #endSpan} to close
     *  the span. The {@code attrs} map is shallow-copied so the
     *  caller cannot mutate the recorded span after the fact. */
    public String startSpan(String name, Map<String, Object> attrs) {
        return startChildSpan(null, name, attrs);
    }

    /** Begin a new child span. The {@code parentSpanId} is recorded
     *  in the span so the TUI can render a tree. The parent need
     *  not be in-flight or even known to the recorder — we accept
     *  any non-null string. Unknown parent ids are rendered as
     *  "dangling" by the TUI (a tree node whose parent is missing
     *  from the visible spans). */
    public String startChildSpan(String parentSpanId, String name, Map<String, Object> attrs) {
        String traceId = "tr-" + UUID.randomUUID();
        Map<String, Object> safeAttrs = attrs == null ? Map.of() : new LinkedHashMap<>(attrs);
        inFlight.put(traceId, new Span(
                traceId, name, parentSpanId, System.currentTimeMillis(), 0L, "running", safeAttrs));
        return traceId;
    }

    /** End a span previously started with {@link #startSpan} or
     *  {@link #startChildSpan}. Unknown / already-ended ids are
     *  silently ignored — the recorder is best-effort and must not
     *  break the engine. Status is normalised: any value other than
     *  {@code "error"} is recorded as {@code "ok"}.
     *
     *  <p>{@code synchronized} so the size check + addLast +
     *  pollFirst are atomic. {@link ConcurrentLinkedDeque} alone
     *  isn't enough — its individual ops are thread-safe but the
     *  size-guard + addLast pair can let the deque briefly exceed
     *  {@link #CAPACITY} under concurrent writers (caught by
     *  {@code TraceRecorderTest.concurrentStartEndIsThreadSafe}). */
    public synchronized void endSpan(String traceId, String status) {
        if (traceId == null) return;
        Span open = inFlight.remove(traceId);
        if (open == null) return; // unknown or already ended
        String finalStatus = "error".equals(status) ? "error" : "ok";
        Span closed = new Span(
                open.traceId,
                open.name,
                open.parentSpanId,
                open.startMs,
                System.currentTimeMillis(),
                finalStatus,
                open.attrs);
        // Bounded retention: if we're at capacity, drop the oldest.
        if (completed.size() >= CAPACITY) {
            completed.pollFirst();
        }
        completed.addLast(closed);
    }

    /** Number of in-flight (still running) spans. */
    public int inFlightCount() {
        return inFlight.size();
    }

    /** Number of completed spans currently retained. */
    public int completedCount() {
        return completed.size();
    }

    /** Return the most recent {@code limit} completed spans, newest
     *  first. {@code limit <= 0} returns an empty list. */
    public List<Span> recentTraces(int limit) {
        if (limit <= 0) return List.of();
        List<Span> out = new ArrayList<>(Math.min(limit, completed.size()));
        // Walk the deque from the tail (newest).
        var it = completed.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            out.add(it.next());
        }
        return out;
    }

    /** return a single trace (one root span + all of its
     *  descendants currently retained in the deque). The result
     *  is a list starting with the root (if present), followed by
     *  its descendants in BFS order (each parent's children,
     *  then each child's children). The root need not be present:
     *  if it was evicted by the bounded deque, the surviving
     *  descendants are still returned (the TUI can then render
     *  them as "orphan" nodes).
     *
     *  <p>If nothing matches (unknown id or all evicted), the
     *  result is empty.
     *
     *  <p>NOTE: an in-flight root span is not returned here — the
     *  TUI's "show the tree for this run" flow is best-effort and
     *  only sees completed spans. (A query that's still running
     *  hasn't finished its tool calls yet, so the tree is
     *  necessarily incomplete.) */
    public List<Span> getTrace(String traceId) {
        if (traceId == null || traceId.isEmpty()) return List.of();
        // Index every completed span by id so we can look up the
        // Span object from a traceId. We need this for both the
        // root and the BFS emission (the deque order is end-time
        // order, not tree order).
        Map<String, Span> byId = new LinkedHashMap<>();
        Span root = null;
        for (Span s : completed) {
            byId.put(s.traceId, s);
            if (s.traceId.equals(traceId)) root = s;
        }
        List<Span> result = new ArrayList<>();
        if (root != null) result.add(root);
        // BFS: each iteration finds spans whose parent is in the
        // current frontier. We emit them in the order they appear
        // in the deque, then advance the frontier.
        java.util.Set<String> emitted = new java.util.HashSet<>();
        if (root != null) emitted.add(root.traceId);
        java.util.Set<String> frontier = new java.util.HashSet<>();
        frontier.add(traceId);
        while (!frontier.isEmpty()) {
            java.util.Set<String> next = new java.util.HashSet<>();
            for (Span s : completed) {
                if (s.parentSpanId != null
                        && frontier.contains(s.parentSpanId)
                        && !emitted.contains(s.traceId)) {
                    result.add(s);
                    emitted.add(s.traceId);
                    next.add(s.traceId);
                }
            }
            frontier = next;
        }
        return result;
    }

    /** Return a JSON-friendly snapshot of the recorder. Used by the
     *  {@code getTraces} RPC and the {@code /trace} command. The
     *  {@code traces} array is the most recent 10 completed spans,
     *  newest first. */
    public Map<String, Object> snapshot() {
        return snapshot(10);
    }

    /** Same as {@link #snapshot()} but with a caller-supplied
     *  trace count. */
    public Map<String, Object> snapshot(int traceLimit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inFlight",   inFlightCount());
        m.put("completed",  completedCount());
        List<Map<String, Object>> traces = new ArrayList<>();
        for (Span s : recentTraces(traceLimit)) {
            traces.add(s.toMap());
        }
        m.put("traces", traces);
        return m;
    }

    /** snapshot for a single trace. Returns
     *  {@code {traceId, inFlight, completed, spans: [...]}}. The
     *  {@code inFlight} / {@code completed} numbers here reflect
     *  the *whole* recorder, not just the requested trace — useful
     *  for the TUI header line. */
    public Map<String, Object> snapshotForTrace(String traceId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traceId",   traceId);
        m.put("inFlight",  inFlightCount());
        m.put("completed", completedCount());
        List<Map<String, Object>> spans = new ArrayList<>();
        for (Span s : getTrace(traceId)) {
            spans.add(s.toMap());
        }
        m.put("spans", spans);
        return m;
    }
}
