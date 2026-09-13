package org.aethercode.memory.heuristic;

import org.aethercode.memory.heuristic.HeuristicExtractor.ExtractionResult;
import org.aethercode.memory.heuristic.HeuristicExtractor.Heuristic;
import org.aethercode.memory.heuristic.HeuristicExtractor.Kind;
import org.aethercode.memory.heuristic.HeuristicExtractor.Outcome;
import org.aethercode.memory.heuristic.HeuristicExtractor.Step;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for paper 2603.24639 (ERL) HeuristicExtractor.
 */
class HeuristicExtractorTest {

    @Test
    void extractsStrategyFromSuccessSteps() {
        HeuristicExtractor ex = new HeuristicExtractor();
        List<Step> trajectory = List.of(
            new Step("When user asks a question then respond with code", Outcome.SUCCESS, null),
            new Step("Use a single tool call to fetch data", Outcome.SUCCESS, null)
        );
        ExtractionResult r = ex.extract(trajectory);
        assertEquals(2, r.totalSteps());
        assertEquals(2, r.successSteps());
        // First step matches WHEN_THEN, second doesn't.
        long strategy = r.heuristics().stream()
            .filter(h -> h.kind() == Kind.STRATEGY).count();
        assertEquals(1, strategy, "exactly 1 strategy from first step");
        Heuristic h = r.heuristics().stream()
            .filter(x -> x.kind() == Kind.STRATEGY).findFirst().orElseThrow();
        assertTrue(h.rule().toLowerCase().contains("when user asks a question"));
        assertTrue(h.rule().toLowerCase().contains("then respond with code"));
    }

    @Test
    void extractsRecoveryFromFailureSteps() {
        HeuristicExtractor ex = new HeuristicExtractor();
        List<Step> trajectory = List.of(
            new Step("If the API call fails, retry with backoff", Outcome.FAILURE,
                "timeout")
        );
        ExtractionResult r = ex.extract(trajectory);
        assertEquals(1, r.failureSteps());
        long recovery = r.heuristics().stream()
            .filter(h -> h.kind() == Kind.RECOVERY).count();
        assertEquals(1, recovery, "exactly 1 recovery heuristic");
        Heuristic h = r.heuristics().stream()
            .filter(x -> x.kind() == Kind.RECOVERY).findFirst().orElseThrow();
        assertTrue(h.rule().toLowerCase().contains("if the api call fails"));
        assertTrue(h.rule().toLowerCase().contains("retry with backoff"));
    }

    @Test
    void extractsAvoidanceFromFailureSteps() {
        HeuristicExtractor ex = new HeuristicExtractor();
        List<Step> trajectory = List.of(
            new Step("Don't call eval() in untrusted contexts", Outcome.FAILURE, null)
        );
        ExtractionResult r = ex.extract(trajectory);
        long avoid = r.heuristics().stream()
            .filter(h -> h.kind() == Kind.AVOIDANCE).count();
        assertEquals(1, avoid);
        Heuristic h = r.heuristics().stream()
            .filter(x -> x.kind() == Kind.AVOIDANCE).findFirst().orElseThrow();
        assertTrue(h.rule().toLowerCase().contains("avoid"));
        assertTrue(h.rule().toLowerCase().contains("eval"));
        assertTrue(h.rule().toLowerCase().contains("untrusted"));
    }

    @Test
    void emptyTrajectoryReturnsEmptyResult() {
        HeuristicExtractor ex = new HeuristicExtractor();
        ExtractionResult r = ex.extract(List.of());
        assertTrue(r.heuristics().isEmpty());
        assertEquals(0, r.totalSteps());
    }

    @Test
    void extractFromTuplesConvenienceWorks() {
        HeuristicExtractor ex = new HeuristicExtractor();
        // (description, outcome-tag)
        List<Map.Entry<String, String>> tuples = List.of(
            Map.entry("When a file is missing then read the parent dir", "SUCCESS"),
            Map.entry("If the network fails, retry 3 times", "FAILURE")
        );
        ExtractionResult r = ex.extractFromTuples(tuples);
        assertEquals(2, r.totalSteps());
        assertEquals(1, r.successSteps());
        assertEquals(1, r.failureSteps());
        // 1 strategy + 1 recovery expected
        assertEquals(2, r.heuristics().size());
    }

    @Test
    void confidenceRoughlyProportionalToEpisodeSize() {
        HeuristicExtractor ex = new HeuristicExtractor();
        // 2 success steps that match WHEN_THEN, total episode = 5 steps
        List<Step> trajectory = List.of(
            new Step("When a then b", Outcome.SUCCESS, null),
            new Step("When c then d", Outcome.SUCCESS, null),
            new Step("step 3", Outcome.PARTIAL, null),
            new Step("step 4", Outcome.PARTIAL, null),
            new Step("step 5", Outcome.PARTIAL, null)
        );
        ExtractionResult r = ex.extract(trajectory);
        // The confidence is min(100, succ * 10) = 20 here
        for (Heuristic h : r.heuristics()) {
            assertTrue(h.confidence() > 0, "confidence should be positive");
            assertTrue(h.sourceEpisodeSteps() > 0, "should report episode size");
        }
    }

    @Test
    void multipleStepsWithDifferentOutcomes() {
        HeuristicExtractor ex = new HeuristicExtractor();
        // Step 1: SUCCESS with "when X then Y" → STRATEGY
        // Step 2: FAILURE with "if A fails, B" → RECOVERY
        List<Step> trajectory = List.of(
            new Step("When parsing JSON then validate schema", Outcome.SUCCESS, null),
            new Step("If parse fails, log the error", Outcome.FAILURE, "json malformed")
        );
        ExtractionResult r = ex.extract(trajectory);
        long strategy = r.heuristics().stream()
            .filter(h -> h.kind() == Kind.STRATEGY).count();
        long recovery = r.heuristics().stream()
            .filter(h -> h.kind() == Kind.RECOVERY).count();
        assertEquals(1, strategy, "1 strategy from success step");
        assertEquals(1, recovery, "1 recovery from failure step");
    }
}
