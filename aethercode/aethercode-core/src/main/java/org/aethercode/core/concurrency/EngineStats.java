package org.aethercode.core.concurrency;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * live engine health snapshot. The daemon's
 * {@code getEngineStats} RPC returns this so the desktop's
 * StatusBar can show a memory badge, a "throttled" pill, and a
 * concurrency count. The fields are updated in-place by the
 * {@link ConcurrencyController}; readers see the latest snapshot
 * without a lock.
 *
 * <p>The class is intentionally small — a record-style read-only
 * view that the controller builds on every {@code snapshot()}
 * call. Callers should not retain the result; read it, render
 * it, drop it.
 */
public final class EngineStats {

    /** Bytes of JVM heap currently used (post-GC). */
    public final long memUsedBytes;
    /** Maximum bytes the JVM may allocate. {@code -1} when the
     *  limit is unknown (very old JVMs). */
    public final long memMaxBytes;
    /** {@code memUsedBytes * 100 / memMaxBytes}, clamped to
     *  {@code [0, 100]}. {@code 0} when the limit is unknown. */
    public final int memPct;
    /** Convenience: {@code memUsedBytes / (1024*1024)}. */
    public final int memUsedMb;
    /** Convenience: {@code memMaxBytes / (1024*1024)}, or
     *  {@code -1} when the JVM didn't report a max. */
    public final int memMaxMb;
    /** 0-100. Engine is throttled above this percentage; new
     *  queries get queued rather than rejected. */
    public final int throttleThresholdPct;
    /** 0-100. Engine is backpressured above this percentage;
     *  new queries return {@code BACKPRESSURE} immediately. */
    public final int backpressureThresholdPct;
    /** True when {@code memPct >= backpressureThresholdPct}.
     *  The StatusBar surfaces this with a "⚠ Memory 92% — 限流中"
     *  pill. */
    public final boolean backpressured;
    /** True when {@code memPct >= throttleThresholdPct} but below
     *  backpressure. The engine is still accepting queries but
     *  marks them as "low priority" (a future R108+ could route
     *  them through a slower path). */
    public final boolean throttled;
    /** Number of queries currently being processed (acquired
     *  the {@code queries} semaphore). */
    public final int queriesInFlight;
    /** Maximum number of queries that can run concurrently. */
    public final int maxConcurrentQueries;
    /** Number of tool subprocesses currently in flight. */
    public final int toolsInFlight;
    /** Maximum number of tool subprocesses that can run
     *  concurrently. */
    public final int maxConcurrentTools;
    /** Number of workflow parallel branches currently in flight. */
    public final int branchesInFlight;
    /** Maximum number of workflow parallel branches. */
    public final int maxConcurrentBranches;
    /** Current concurrency profile name (e.g. "low" / "normal" /
     *  "high"). */
    public final String concurrencyProfile;
    /** Last refresh time in epoch milliseconds. */
    public final long sampledAtMs;

    public EngineStats(
            long memUsedBytes, long memMaxBytes, int memPct,
            int throttleThresholdPct, int backpressureThresholdPct,
            boolean throttled, boolean backpressured,
            int queriesInFlight, int maxConcurrentQueries,
            int toolsInFlight, int maxConcurrentTools,
            int branchesInFlight, int maxConcurrentBranches,
            String concurrencyProfile, long sampledAtMs) {
        this.memUsedBytes = memUsedBytes;
        this.memMaxBytes = memMaxBytes;
        this.memPct = memPct;
        this.memUsedMb = (int) (memUsedBytes / (1024 * 1024));
        this.memMaxMb = memMaxBytes > 0 ? (int) (memMaxBytes / (1024 * 1024)) : -1;
        this.throttleThresholdPct = throttleThresholdPct;
        this.backpressureThresholdPct = backpressureThresholdPct;
        this.throttled = throttled;
        this.backpressured = backpressured;
        this.queriesInFlight = queriesInFlight;
        this.maxConcurrentQueries = maxConcurrentQueries;
        this.toolsInFlight = toolsInFlight;
        this.maxConcurrentTools = maxConcurrentTools;
        this.branchesInFlight = branchesInFlight;
        this.maxConcurrentBranches = maxConcurrentBranches;
        this.concurrencyProfile = concurrencyProfile;
        this.sampledAtMs = sampledAtMs;
    }

    public Map<String, Object> toMap() {
        // Use LinkedHashMap for stable serialisation in the
        // JSON-RPC response — the desktop reads fields by
        // name, but a consistent field order helps debugging.
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("memUsedBytes", memUsedBytes);
        m.put("memMaxBytes", memMaxBytes);
        m.put("memUsedMb", memUsedBytes / (1024 * 1024));
        m.put("memMaxMb", memMaxBytes > 0 ? memMaxBytes / (1024 * 1024) : -1);
        m.put("memPct", memPct);
        m.put("throttleThresholdPct", throttleThresholdPct);
        m.put("backpressureThresholdPct", backpressureThresholdPct);
        m.put("throttled", throttled);
        m.put("backpressured", backpressured);
        m.put("queriesInFlight", queriesInFlight);
        m.put("maxConcurrentQueries", maxConcurrentQueries);
        m.put("toolsInFlight", toolsInFlight);
        m.put("maxConcurrentTools", maxConcurrentTools);
        m.put("branchesInFlight", branchesInFlight);
        m.put("maxConcurrentBranches", maxConcurrentBranches);
        m.put("concurrencyProfile", concurrencyProfile);
        m.put("sampledAtMs", sampledAtMs);
        return m;
    }

    // Suppress unused-import warning for AtomicInteger — the
    // controller uses it; keeping the import here documents the
    // class's intent.
    @SuppressWarnings("unused")
    private static final AtomicInteger COUNTER = new AtomicInteger();
}
