package org.aethercode.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link SessionSummary}. Combines TaskStats
 * and PlanStats into a one-line summary.
 */
class SessionSummaryTest {

    @Test
    void oneLine_basicCase() {
        TaskStats stats = new TaskStats(5, 1, 2, 2, 0, 0, 1, 100L);
        String line = SessionSummary.oneLine(stats, null);
        assertTrue(line.contains("5 tasks"));
        assertTrue(line.contains("2 running"));
        assertTrue(line.contains("1 queued"));
        assertFalse(line.contains("plan"));
    }

    @Test
    void oneLine_withPlan() {
        TaskStats stats = new TaskStats(3, 0, 0, 3, 0, 0, 1, 0L);
        String planSummary = "3 steps in 5.0s (avg 1.7s)";
        String line = SessionSummary.oneLine(stats, planSummary);
        assertTrue(line.contains("3 tasks"));
        assertTrue(line.contains("plan:"));
    }

    @Test
    void oneLine_noStats() {
        String line = SessionSummary.oneLine(null, null);
        assertEquals("0 tasks", line);
    }

    @Test
    void breakdown_includesTrend() {
        StatsHistory h = new StatsHistory(5);
        h.record(new TaskStats(3, 0, 0, 0, 0, 0, 0, 0L));
        h.record(new TaskStats(5, 0, 0, 0, 0, 0, 0, 0L));
        TaskStats stats = new TaskStats(5, 0, 0, 0, 0, 0, 0, 0L);
        List<String> lines = SessionSummary.breakdown(stats, null, h);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("trend:")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("↑")));
    }

    @Test
    void breakdown_handlesNulls() {
        List<String> lines = SessionSummary.breakdown(null, null, null);
        assertTrue(lines.isEmpty());
    }
}
