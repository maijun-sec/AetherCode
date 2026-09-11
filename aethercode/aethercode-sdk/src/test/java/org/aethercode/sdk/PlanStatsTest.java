package org.aethercode.sdk;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link PlanStats}. Verifies counting, slowest-step
 * detection, retry detection, and the human-readable summary.
 */
class PlanStatsTest {

    @Test
    void emptyResults_returnsEmptyStats() {
        PlanStats s = PlanStats.from(List.of());
        assertEquals(0, s.totalSteps());
        assertEquals(-1, s.slowestStepIndex());
        assertEquals("empty plan", s.summary());
    }

    @Test
    void nullResults_returnsEmptyStats() {
        PlanStats s = PlanStats.from(null);
        assertEquals(0, s.totalSteps());
        assertEquals("empty plan", s.summary());
    }

    @Test
    void countsOutcomesCorrectly() {
        List<PlanExecutor.StepResult> results = List.of(
                new PlanExecutor.StepResult(0, "a", PlanExecutor.StepOutcome.COMPLETED, "ok", 100L),
                new PlanExecutor.StepResult(1, "b", PlanExecutor.StepOutcome.COMPLETED, "ok", 200L),
                new PlanExecutor.StepResult(2, "c", PlanExecutor.StepOutcome.FAILED, "boom", 50L),
                new PlanExecutor.StepResult(3, "d", PlanExecutor.StepOutcome.SKIPPED, "skipped", 0L)
        );
        PlanStats s = PlanStats.from(results);
        assertEquals(4, s.totalSteps());
        assertEquals(2, s.completedSteps());
        assertEquals(1, s.failedSteps());
        assertEquals(1, s.skippedSteps());
        assertEquals(350L, s.totalElapsedMs());
        assertEquals(87L, s.avgStepMs()); // 350/4 = 87
    }

    @Test
    void findsSlowestStep() {
        List<PlanExecutor.StepResult> results = List.of(
                new PlanExecutor.StepResult(0, "a", PlanExecutor.StepOutcome.COMPLETED, "ok", 100L),
                new PlanExecutor.StepResult(1, "b", PlanExecutor.StepOutcome.COMPLETED, "ok", 9999L),
                new PlanExecutor.StepResult(2, "c", PlanExecutor.StepOutcome.COMPLETED, "ok", 200L)
        );
        PlanStats s = PlanStats.from(results);
        assertEquals(1, s.slowestStepIndex());
        assertEquals(9999L, s.slowestStepMs());
    }

    @Test
    void detectsRetriesFromDetailPrefix() {
        // PlanExecutor annotates retried steps with "[retried Nx] ..."
        // — PlanStats picks that up.
        List<PlanExecutor.StepResult> results = List.of(
                new PlanExecutor.StepResult(0, "a", PlanExecutor.StepOutcome.COMPLETED, "ok", 100L),
                new PlanExecutor.StepResult(1, "b", PlanExecutor.StepOutcome.COMPLETED, "[retried 2x] ok", 200L),
                new PlanExecutor.StepResult(2, "c", PlanExecutor.StepOutcome.COMPLETED, "[retried 1x] ok", 300L)
        );
        PlanStats s = PlanStats.from(results);
        assertEquals(2, s.retriedSteps());
    }

    @Test
    void summary_typicalPlan() {
        List<PlanExecutor.StepResult> results = List.of(
                new PlanExecutor.StepResult(0, "a", PlanExecutor.StepOutcome.COMPLETED, "ok", 1_000L),
                new PlanExecutor.StepResult(1, "b", PlanExecutor.StepOutcome.COMPLETED, "[retried 1x] ok", 3_000L),
                new PlanExecutor.StepResult(2, "c", PlanExecutor.StepOutcome.FAILED, "boom", 500L)
        );
        PlanStats s = PlanStats.from(results);
        String summary = s.summary();
        assertTrue(summary.contains("3 steps"));
        assertTrue(summary.contains("2 ok"));
        assertTrue(summary.contains("1 failed"));
        assertTrue(summary.contains("1 retried"));
        assertTrue(summary.contains("slowest step 2")); // step 2 was index 1, +1 = 2
    }

    @Test
    void summary_singleStep() {
        // One step — no "avg" or "slowest" should appear.
        List<PlanExecutor.StepResult> results = List.of(
                new PlanExecutor.StepResult(0, "a", PlanExecutor.StepOutcome.COMPLETED, "ok", 100L)
        );
        PlanStats s = PlanStats.from(results);
        String summary = s.summary();
        assertTrue(summary.contains("1 step")); // singular
        assertFalse(summary.contains("avg"));
        assertFalse(summary.contains("slowest"));
    }

    @Test
    void summary_formatsLongDurations() {
        // 65s = 1m5s format
        List<PlanExecutor.StepResult> results = List.of(
                new PlanExecutor.StepResult(0, "a", PlanExecutor.StepOutcome.COMPLETED, "ok", 65_000L)
        );
        PlanStats s = PlanStats.from(results);
        assertTrue(s.summary().contains("1m5s"),
                "summary should use m+s format for >= 60s, got: " + s.summary());
    }

    // ETA prediction

    @Test
    void etaMs_multipliesAvgByRemaining() {
        // Construct a PlanStats directly: 5 steps total, 2 completed
        // (avg 1000ms), 3 remaining → ETA = 3000ms
        PlanStats s = new PlanStats(
                5, 2, 0, 0, 0, 2000L, 1000L, 1000L, 0);
        assertEquals(3, s.remainingSteps());
        assertEquals(3_000L, s.etaMs());
    }

    @Test
    void etaMs_returnsZeroWhenAllDone() {
        // 1 step total, 1 completed → 0 remaining → ETA = 0
        PlanStats s = new PlanStats(
                1, 1, 0, 0, 0, 1000L, 1000L, 1000L, 0);
        assertEquals(0, s.remainingSteps());
        assertEquals(0L, s.etaMs());
    }

    @Test
    void etaPessimisticMs_usesSlowestStep() {
        // 4 steps total, 3 completed (avg=7000/3, slowest=5000),
        // 1 remaining
        PlanStats s = new PlanStats(
                4, 3, 0, 0, 0, 7000L, 2333L, 5000L, 2);
        // avg-based: 2333 * 1 ≈ 2333
        assertEquals(2333L, s.etaMs());
        // pessimistic: 5000 * 1
        assertEquals(5000L, s.etaPessimisticMs());
    }

    // median + percentile

    @Test
    void medianStepMs_returnsMiddleValue() {
        List<PlanExecutor.StepResult> results = List.of(
                new PlanExecutor.StepResult(0, "a", PlanExecutor.StepOutcome.COMPLETED, "", 100L),
                new PlanExecutor.StepResult(1, "b", PlanExecutor.StepOutcome.COMPLETED, "", 200L),
                new PlanExecutor.StepResult(2, "c", PlanExecutor.StepOutcome.COMPLETED, "", 500L)
        );
        // middle of [100, 200, 500] = 200
        assertEquals(200L, PlanStats.from(results).medianStepMs(results));
    }

    @Test
    void percentile_p50AndP90() {
        // 10 steps, 100ms..1000ms in 100ms increments
        java.util.List<PlanExecutor.StepResult> results = new java.util.ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            results.add(new PlanExecutor.StepResult(i - 1, "s" + i,
                    PlanExecutor.StepOutcome.COMPLETED, "", i * 100L));
        }
        PlanStats s = PlanStats.from(results);
        // p50 → middle → 500ms
        assertEquals(500L, s.percentile(results, 50));
        // p90 → near end → 900ms
        assertEquals(900L, s.percentile(results, 90));
    }
}
