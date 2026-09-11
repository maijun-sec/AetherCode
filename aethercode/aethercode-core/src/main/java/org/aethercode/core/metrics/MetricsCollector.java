package org.aethercode.core.metrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * simple in-memory metrics collector.
 *
 * Counts the most useful per-session operations: turns, tool calls,
 * tool errors, tool retries, and approximate cost. Thread-safe via
 * {@link AtomicLong}; the user-facing snapshot is taken with a
 * single volatile read per counter.
 *
 * This is intentionally not Prometheus-shaped — we don't have
 * histograms, labels, or aggregation. It's the smallest thing that
 * gives the user a sense of "what has the engine done so far",
 * which is what the TUI's /metrics command needs.
 *
 * Scope: per-process (per-daemon). The collector is created when
 * the engine starts and never reset. The TUI displays a snapshot
 * at request time.
 */
public final class MetricsCollector {

    // Per-process counters.
    private final AtomicLong turnsStarted   = new AtomicLong();
    private final AtomicLong turnsCompleted = new AtomicLong();
    private final AtomicLong toolCalls      = new AtomicLong();
    private final AtomicLong toolErrors     = new AtomicLong();
    private final AtomicLong toolRetries    = new AtomicLong();
    private final AtomicLong loopStops      = new AtomicLong();
    private final AtomicLong permissionAsks = new AtomicLong();
    private final AtomicLong permissionDenies = new AtomicLong();
    private final AtomicLong cacheHits      = new AtomicLong();
    private final AtomicLong cacheMisses    = new AtomicLong();
    /** cumulative input tokens (across all LLM calls).
     *  Populated by {@link #addTokens(int, int)} when the chat
     *  client emits a {@code StreamEvent.Usage} event. */
    private final AtomicLong inputTokens    = new AtomicLong();
    /** cumulative output tokens. */
    private final AtomicLong outputTokens   = new AtomicLong();

    // Cost is tracked as a double because cents precision isn't needed.
    private final java.util.concurrent.atomic.AtomicReference<Double> costUsd =
        new java.util.concurrent.atomic.AtomicReference<>(0.0);

    private final long startedAtMs = System.currentTimeMillis();

    public void incTurnStarted()   { turnsStarted.incrementAndGet(); }
    public void incTurnCompleted() { turnsCompleted.incrementAndGet(); }
    public void incToolCall()      { toolCalls.incrementAndGet(); }
    public void incToolError()     { toolErrors.incrementAndGet(); }
    public void incToolRetry()     { toolRetries.incrementAndGet(); }
    public void incLoopStop()      { loopStops.incrementAndGet(); }
    public void incPermissionAsk() { permissionAsks.incrementAndGet(); }
    public void incPermissionDeny() { permissionDenies.incrementAndGet(); }
    public void incCacheHit()      { cacheHits.incrementAndGet(); }
    public void incCacheMiss()     { cacheMisses.incrementAndGet(); }

    /** record per-call token usage from the chat client. Negative
     *  values are clamped to 0. */
    public void addTokens(int input, int output) {
        if (input > 0)  inputTokens.addAndGet(input);
        if (output > 0) outputTokens.addAndGet(output);
    }

    public long inputTokens()  { return inputTokens.get(); }
    public long outputTokens() { return outputTokens.get(); }
    public long totalTokens()  { return inputTokens.get() + outputTokens.get(); }

    /** Add cost in USD. Negative values are rejected. */
    public void addCostUsd(double usd) {
        if (!Double.isFinite(usd) || usd < 0) return;
        costUsd.updateAndGet((c) -> c + usd);
    }

    /** Take a snapshot of all counters. The returned map is a copy
     *  — the caller can mutate it freely. */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uptimeMs",          System.currentTimeMillis() - startedAtMs);
        m.put("turnsStarted",      turnsStarted.get());
        m.put("turnsCompleted",    turnsCompleted.get());
        m.put("toolCalls",         toolCalls.get());
        m.put("toolErrors",        toolErrors.get());
        m.put("toolRetries",       toolRetries.get());
        m.put("loopStops",         loopStops.get());
        m.put("permissionAsks",    permissionAsks.get());
        m.put("permissionDenies",  permissionDenies.get());
        m.put("cacheHits",         cacheHits.get());
        m.put("cacheMisses",       cacheMisses.get());
        m.put("inputTokens",       inputTokens.get());
        m.put("outputTokens",      outputTokens.get());
        m.put("totalTokens",       inputTokens.get() + outputTokens.get());
        m.put("costUsd",           costUsd.get());
        m.put("errorRate",         ratio(toolErrors.get(), toolCalls.get()));
        m.put("cacheHitRate",      ratio(cacheHits.get(), cacheHits.get() + cacheMisses.get()));
        // aliases for the desktop UI's TS MetricsSnapshot type
        // (which uses the friendlier "totalQueries" / "totalToolCalls"
        // names). Without these, the TokenUsage panel and
        // StatusBar show "—" for these counters even after a run.
        m.put("totalQueries",      turnsStarted.get());
        m.put("totalToolCalls",    toolCalls.get());
        return m;
    }

    /** Compute ratio (numerator / denominator) safely. Returns 0
     *  when denominator is 0. */
    private static double ratio(long n, long d) {
        return d <= 0 ? 0.0 : (double) n / (double) d;
    }
}
