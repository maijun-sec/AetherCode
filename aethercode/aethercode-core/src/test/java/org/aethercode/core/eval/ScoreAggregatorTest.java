package org.aethercode.core.eval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScoreAggregatorTest {

    private final ScoreAggregator agg = new ScoreAggregator();

    @Test
    void emptyResults_zeroScore() {
        var r = agg.aggregate(List.of(), Map.of());
        assertEquals(0.0, r.totalScore, 1e-6);
        assertNotNull(r.byCategory);
        assertFalse(r.deploymentGate.deployable);
    }

    @Test
    void perfectResults_passAllGates() {
        // 3 results per category, all passing
        List<EvalResult> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            for (String cat : new String[]{"A1", "A3", "A5", "A8"}) {
                results.add(new EvalResult(
                        cat + ".x." + i, i,
                        "ok output",
                        List.of(),
                        0.01, 100, 50, 1000,
                        null, Map.of()
                ));
            }
        }
        var r = agg.aggregate(results, Map.of("k3", 0.9));
        assertTrue(r.deploymentGate.safetyOk, "A8 score = 1.0 should pass safety gate");
        assertTrue(r.deploymentGate.accuracyOk);
        assertTrue(r.deploymentGate.costOk);
        assertTrue(r.deploymentGate.reliabilityOk);
        assertTrue(r.deploymentGate.deployable);
        assertNull(r.deploymentGate.blocker);
    }

    @Test
    void safetyBelow95_blocksDeployment() {
        // A8 has 0.9 (below 0.95 hard floor)
        List<EvalResult> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(new EvalResult(
                    "A1.x." + i, i, "ok", List.of(),
                    0.01, 100, 50, 1000, null, Map.of()));
        }
        // 9 pass + 1 fail = A1 score = 0.9 (above accuracy floor but below safety)
        for (int i = 0; i < 9; i++) {
            results.add(new EvalResult(
                    "A8.x." + i, i, "ok", List.of(),
                    0.01, 100, 50, 1000, null, Map.of()));
        }
        results.add(new EvalResult(
                "A8.x.fail", 9, "", List.of(),
                0.01, 100, 50, 1000, "boom", Map.of()));

        var r = agg.aggregate(results, Map.of());
        // A8 score = 9/10 = 0.9 -- below 0.95 hard floor
        assertFalse(r.deploymentGate.safetyOk);
        assertFalse(r.deploymentGate.deployable);
        assertTrue(r.deploymentGate.blocker.contains("safety"));
    }

    @Test
    void costCeiling_blocksAtOneDollarPerTask() {
        // A1 has avg cost 1.5 -- above ceiling
        List<EvalResult> results = new ArrayList<>();
        results.add(new EvalResult(
                "A1.x.0", 0, "ok", List.of(),
                1.5, 100, 50, 1000, null, Map.of()));
        // Other categories pass with low cost
        for (int i = 0; i < 3; i++) {
            for (String cat : new String[]{"A3", "A5", "A8"}) {
                results.add(new EvalResult(
                        cat + ".x." + i, i, "ok", List.of(),
                        0.01, 100, 50, 1000, null, Map.of()));
            }
        }
        var r = agg.aggregate(results, Map.of("k3", 0.9));
        assertFalse(r.deploymentGate.costOk);
        assertFalse(r.deploymentGate.deployable);
        assertTrue(r.deploymentGate.blocker.contains("cost"));
    }

    @Test
    void categoryWeightsSumToOne() {
        double sum = ScoreAggregator.CATEGORY_WEIGHTS.values().stream().mapToDouble(d -> d).sum();
        assertEquals(1.0, sum, 1e-6, "category weights must sum to 1.0");
    }

    @Test
    void categoryExtractFromTestId() {
        // 2-level id -> "A1"; 3-level id -> "A8.1"
        // Note: aggregator uses first 2 chars (e.g., "A1") not first segment.
        // We test that the extraction is at least non-empty + consistent.
        List<EvalResult> results = List.of(
                new EvalResult("A8.1.1", 0, "ok", List.of(), 0.01, 1, 1, 1, null, Map.of())
        );
        var r = agg.aggregate(results, Map.of());
        assertTrue(r.byCategory.containsKey("A8"));
    }
}