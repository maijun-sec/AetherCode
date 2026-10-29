/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.core.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for the new token-tracking counters in
 * {@link MetricsCollector}. The legacy counters (turns, tool calls,
 * costUsd) are also re-tested here to lock in the snapshot shape
 * — the desktop UI's TokenUsage panel reads these via getMetrics
 * and the old "—" placeholders only went away once the wiring
 * was complete.
 */
class MetricsCollectorTest {

    @Test
    void freshCollectorIsAllZero() {
        MetricsCollector c = new MetricsCollector();
        var snap = c.snapshot();
        assertEquals(0L, snap.get("inputTokens"));
        assertEquals(0L, snap.get("outputTokens"));
        assertEquals(0L, snap.get("totalTokens"));
        assertEquals(0L, snap.get("turnsStarted"));
        assertEquals(0L, snap.get("toolCalls"));
        assertEquals(0.0, snap.get("costUsd"));
    }

    @Test
    void addTokensAccumulates() {
        MetricsCollector c = new MetricsCollector();
        c.addTokens(100, 50);
        c.addTokens(200, 80);
        c.addTokens(0, 30);
        assertEquals(300L, c.inputTokens());
        assertEquals(160L, c.outputTokens());
        assertEquals(460L, c.totalTokens());
    }

    @Test
    void addTokensIgnoresNegative() {
        MetricsCollector c = new MetricsCollector();
        c.addTokens(-1, -1);
        c.addTokens(50, 25);
        assertEquals(50L, c.inputTokens());
        assertEquals(25L, c.outputTokens());
    }

    @Test
    void snapshotExposesAllCounters() {
        MetricsCollector c = new MetricsCollector();
        c.incTurnStarted();
        c.incTurnCompleted();
        c.incToolCall();
        c.incToolError();
        c.addTokens(1000, 2000);
        c.addCostUsd(0.42);
        var snap = c.snapshot();
        assertEquals(1L, snap.get("turnsStarted"));
        assertEquals(1L, snap.get("turnsCompleted"));
        assertEquals(1L, snap.get("toolCalls"));
        assertEquals(1L, snap.get("toolErrors"));
        assertEquals(1000L, snap.get("inputTokens"));
        assertEquals(2000L, snap.get("outputTokens"));
        assertEquals(3000L, snap.get("totalTokens"));
        assertEquals(0.42, snap.get("costUsd"));
        assertNotNull(snap.get("uptimeMs"));
    }

    @Test
    void freshCollector_exposesR729_autoApprove_andR727_idleTurns_atZero() {
        // R729 + R727: the new metrics must be present in the
        // snapshot even at zero so the UI's TokenUsage panel
        // can render the fields without "—" placeholders.
        MetricsCollector c = new MetricsCollector();
        var snap = c.snapshot();
        assertNotNull(snap.get("permissionAutoApprovals"),
                "R729: fresh snapshot must expose permissionAutoApprovals at 0");
        assertEquals(0L, snap.get("permissionAutoApprovals"));
        assertNotNull(snap.get("idleTurnsDetected"),
                "R727: fresh snapshot must expose idleTurnsDetected at 0");
        assertEquals(0L, snap.get("idleTurnsDetected"));
    }

    @Test
    void incPermissionAutoApprove_accumulatesAcrossRuns() {
        // R729: the StreamingToolExecutor bumps this counter
        // once per Allow it consumes. Verify the simple
        // counter behaviour (the wire-up is exercised by the
        // StreamingToolExecutor tests in this module).
        MetricsCollector c = new MetricsCollector();
        for (int i = 0; i < 7; i++) c.incPermissionAutoApprove();
        assertEquals(7L, c.permissionAutoApprovals());
        assertEquals(7L, c.snapshot().get("permissionAutoApprovals"));
    }

    @Test
    void resetClearsTokens() {
        MetricsCollector c = new MetricsCollector();
        c.addTokens(100, 200);
        // MetricsCollector has no reset() method — the
        // engine keeps accumulating across the daemon's lifetime.
        // We construct a fresh collector to verify the
        // zero-state initialisation. (A real reset() can be
        // added if the engine ever needs one.)
        MetricsCollector fresh = new MetricsCollector();
        assertEquals(0L, fresh.inputTokens());
        assertEquals(0L, fresh.outputTokens());
    }

    @Test
    void addCostUsdRejectsNegativeAndNonFinite() {
        MetricsCollector c = new MetricsCollector();
        c.addCostUsd(1.0);
        c.addCostUsd(-0.5);   // rejected
        c.addCostUsd(Double.NaN);   // rejected
        c.addCostUsd(Double.POSITIVE_INFINITY);   // rejected
        assertEquals(1.0, c.snapshot().get("costUsd"));
    }

    @Test
    void errorRateAndCacheHitRateAreRatios() {
        MetricsCollector c = new MetricsCollector();
        c.incToolCall();
        c.incToolCall();
        c.incToolCall();
        c.incToolError();
        c.incCacheHit();
        c.incCacheHit();
        c.incCacheMiss();
        var snap = c.snapshot();
        assertEquals(1.0 / 3.0, (Double) snap.get("errorRate"), 1e-9);
        assertEquals(2.0 / 3.0, (Double) snap.get("cacheHitRate"), 1e-9);
    }
}
