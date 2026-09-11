package org.aethercode.bridge;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * lightweight metrics for the bridge connection. Modelled on the TS
 * {@code services/bridge/metrics.ts}. Tracks counters and a rolling
 * ping/pong latency summary so the IDE plugin (or the CLI status line)
 * can show a one-line health readout.
 *
 * <p>All counters are monotonic and thread-safe. Latency values are
 * captured in nanoseconds via {@link System#nanoTime()} and exposed in
 * milliseconds.
 */
public class BridgeMetrics {

    private final LongAdder connectAttempts = new LongAdder();
    private final LongAdder connectSuccesses = new LongAdder();
    private final LongAdder reconnects = new LongAdder();
    private final LongAdder authFailures = new LongAdder();
    private final LongAdder toolCalls = new LongAdder();
    private final LongAdder toolErrors = new LongAdder();

    private final LongAdder pingCount = new LongAdder();
    private final LongAdder pingTotalMs = new LongAdder();
    private final AtomicLong pingMinMs = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong pingMaxMs = new AtomicLong(0);
    private final LongAccumulator pingMaxAll = new LongAccumulator(Long::max, 0);

    private final AtomicLong startedAtMs = new AtomicLong(0);

    public void onConnectAttempt() { connectAttempts.increment(); }
    public void onConnectSuccess() { connectSuccesses.increment(); }
    public void onReconnect() { reconnects.increment(); }
    public void onAuthFailure() { authFailures.increment(); }
    public void onToolCall() { toolCalls.increment(); }
    public void onToolError() { toolErrors.increment(); }

    /** record a round-trip latency in nanoseconds (e.g. {@code System.nanoTime()} diff). */
    public void recordPingNanos(long elapsedNanos) {
        if (elapsedNanos < 0) return;
        long ms = elapsedNanos / 1_000_000L;
        pingCount.increment();
        pingTotalMs.add(ms);
        // min — only update when strictly less
        long cur;
        do { cur = pingMinMs.get(); if (cur != Long.MAX_VALUE && ms >= cur) break; }
        while (!pingMinMs.compareAndSet(cur, ms));
        // max — only update when strictly greater
        long curMax;
        do { curMax = pingMaxMs.get(); if (ms <= curMax) break; }
        while (!pingMaxMs.compareAndSet(curMax, ms));
    }

    public void markStarted() { startedAtMs.set(System.currentTimeMillis()); }

    // ---- accessors ----

    public long connectAttempts() { return connectAttempts.sum(); }
    public long connectSuccesses() { return connectSuccesses.sum(); }
    public long reconnects() { return reconnects.sum(); }
    public long authFailures() { return authFailures.sum(); }
    public long toolCalls() { return toolCalls.sum(); }
    public long toolErrors() { return toolErrors.sum(); }
    public long pingCount() { return pingCount.sum(); }
    public long pingTotalMs() { return pingTotalMs.sum(); }
    public long pingMinMs() { long v = pingMinMs.get(); return v == Long.MAX_VALUE ? 0 : v; }
    public long pingMaxMs() { return pingMaxMs.get(); }
    public long pingAvgMs() {
        long c = pingCount.sum();
        return c == 0 ? 0 : pingTotalMs.sum() / c;
    }
    public long uptimeMs() {
        long start = startedAtMs.get();
        return start == 0 ? 0 : System.currentTimeMillis() - start;
    }

    /** a one-line health readout. */
    public String render() {
        return String.format(
                "bridge: connects=%d/%d reconnects=%d auth-fail=%d tools=%d (err=%d) ping avg=%.0fms min=%dms max=%dms uptime=%dms",
                connectSuccesses.sum(), connectAttempts.sum(),
                reconnects.sum(), authFailures.sum(),
                toolCalls.sum(), toolErrors.sum(),
                (double) pingAvgMs(), pingMinMs(), pingMaxMs(),
                uptimeMs());
    }

    public void reset() {
        connectAttempts.reset(); connectSuccesses.reset();
        reconnects.reset(); authFailures.reset();
        toolCalls.reset(); toolErrors.reset();
        pingCount.reset(); pingTotalMs.reset();
        pingMinMs.set(Long.MAX_VALUE);
        pingMaxMs.set(0);
        startedAtMs.set(0);
    }
}
