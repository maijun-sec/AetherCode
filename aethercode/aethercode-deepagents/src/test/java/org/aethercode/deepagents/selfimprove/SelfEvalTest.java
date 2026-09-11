package org.aethercode.deepagents.selfimprove;

import org.aethercode.core.runtime.AgentState;
import org.aethercode.deepagents.tools.Tool;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfEvalTest {

    private static Tool successTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "ok"; }
            @Override public Map<String, Object> argsSchema() { return Map.of(); }
            @Override public Object invoke(Map<String, Object> a) { return "ok"; }
            @Override public Tool withDescription(String s) { return this; }
        };
    }

    private static Tool failingTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "fail"; }
            @Override public Map<String, Object> argsSchema() { return Map.of(); }
            @Override public Object invoke(Map<String, Object> a) {
                throw new RuntimeException("boom");
            }
            @Override public Tool withDescription(String s) { return this; }
        };
    }

    private static Tool nullResultTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "null"; }
            @Override public Map<String, Object> argsSchema() { return Map.of(); }
            @Override public Object invoke(Map<String, Object> a) { return null; }
            @Override public Tool withDescription(String s) { return this; }
        };
    }

    // -----------------------------------------------------------------
    //  ReasoningUnit + confidence math
    // -----------------------------------------------------------------

    @Test
    void confidenceWithZeroObservationsIsSmoothed() {
        // Laplace smoothing: 0 / (0 + 0 + 1) = 0.0, but the
        // +1 in the denominator keeps the score usable.
        ReasoningUnit u = new ReasoningUnit("u1", "k", "e", "f", "",
                0.5, 0L, 0L, 0L, Instant.now());
        assertEquals(0.0, u.confidence(), 1e-9);
    }

    @Test
    void confidenceApproachesOneWithAllOk() {
        ReasoningUnit u = new ReasoningUnit("u1", "k", "e", "f", "",
                0.5, 0L, 9L, 0L, Instant.now());
        // 9 / (9 + 0 + 1) = 0.9
        assertEquals(0.9, u.confidence(), 1e-9);
    }

    @Test
    void confidenceApproachesZeroWithAllNotOk() {
        ReasoningUnit u = new ReasoningUnit("u1", "k", "e", "f", "",
                0.5, 0L, 0L, 9L, Instant.now());
        // 0 / (0 + 9 + 1) = 0.0
        assertEquals(0.0, u.confidence(), 1e-9);
    }

    @Test
    void withOutcomeBumpsTheRightCounter() {
        ReasoningUnit base = new ReasoningUnit("u1", "k", "e", "f", "",
                0.5, 0L, 0L, 0L, Instant.now());
        assertEquals(1L, base.withOutcome(true).okCount());
        assertEquals(0L, base.withOutcome(true).notOkCount());
        assertEquals(0L, base.withOutcome(false).okCount());
        assertEquals(1L, base.withOutcome(false).notOkCount());
    }

    // -----------------------------------------------------------------
    //  ReasoningBank.recordOutcome
    // -----------------------------------------------------------------

    @Test
    void recordOutcomeUnknownIdReturnsEmpty() {
        ReasoningBank bank = new ReasoningBank();
        assertTrue(bank.recordOutcome("does-not-exist", true).isEmpty());
    }

    @Test
    void recordOutcomeUpdatesBankInPlace() {
        ReasoningBank bank = new ReasoningBank();
        bank.add(new ReasoningUnit("u1", "k", "e", "f", "",
                0.5, 0L, 0L, 0L, Instant.now()));
        bank.recordOutcome("u1", true);
        bank.recordOutcome("u1", true);
        bank.recordOutcome("u1", false);
        ReasoningUnit updated = bank.get("u1").orElseThrow();
        assertEquals(2L, updated.okCount());
        assertEquals(1L, updated.notOkCount());
        // 2 / (2 + 1 + 1) = 0.5
        assertEquals(0.5, updated.confidence(), 1e-9);
    }

    // -----------------------------------------------------------------
    //  SelfEvalClassifier
    // -----------------------------------------------------------------

    @Test
    void heuristicClassifiesExceptionAsNotOk() {
        SelfEvalClassifier.Evaluation ev = SelfEvalClassifier.heuristic()
                .classify("t", Map.of(), null, new RuntimeException("x"));
        assertFalse(ev.ok());
        assertTrue(ev.reason().contains("error"));
    }

    @Test
    void heuristicClassifiesNullResultAsNotOk() {
        SelfEvalClassifier.Evaluation ev = SelfEvalClassifier.heuristic()
                .classify("t", Map.of(), null, null);
        assertFalse(ev.ok());
        assertTrue(ev.reason().contains("null"));
    }

    @Test
    void heuristicClassifiesSuccessfulCallAsOk() {
        SelfEvalClassifier.Evaluation ev = SelfEvalClassifier.heuristic()
                .classify("t", Map.of(), "result-text", null);
        assertTrue(ev.ok());
    }

    @Test
    void neverClassifierAlwaysReportsOk() {
        SelfEvalClassifier.Evaluation ev = SelfEvalClassifier.never()
                .classify("t", Map.of(), null, new RuntimeException("x"));
        assertTrue(ev.ok());
    }

    // -----------------------------------------------------------------
    //  SelfEvalMiddleware
    // -----------------------------------------------------------------

    @Test
    void successfulCallBumpsOkCountOnRecalledUnits() throws Exception {
        ReasoningBank bank = new ReasoningBank();
        ReasoningUnit u = bank.add(new ReasoningUnit(
                "u1", "file_edit", "err", "fix", "ex",
                0.9, 0L, 0L, 0L, Instant.now()));
        BankRecallMiddleware recall = new BankRecallMiddleware(bank);
        SelfEvalMiddleware mw = new SelfEvalMiddleware(bank, recall);
        mw.wrapToolCall(successTool("read_file"), Map.of(),
                AgentState.empty(), null);
        assertEquals(1, mw.totalEvaluations());
        assertEquals(1, mw.totalOk());
        assertEquals(0, mw.totalNotOk());
        ReasoningUnit updated = bank.get("u1").orElseThrow();
        assertEquals(1L, updated.okCount());
    }

    @Test
    void failingCallBumpsNotOkCount() {
        ReasoningBank bank = new ReasoningBank();
        bank.add(new ReasoningUnit("u1", "k", "e", "f", "x",
                0.9, 0L, 0L, 0L, Instant.now()));
        BankRecallMiddleware recall = new BankRecallMiddleware(bank);
        SelfEvalMiddleware mw = new SelfEvalMiddleware(bank, recall);
        try {
            mw.wrapToolCall(failingTool("read_file"), Map.of(),
                    AgentState.empty(), null);
        } catch (Exception expected) {
            // expected
        }
        assertEquals(1, mw.totalNotOk());
        ReasoningUnit updated = bank.get("u1").orElseThrow();
        assertEquals(1L, updated.notOkCount());
    }

    @Test
    void disabledMiddlewareSkipsEvaluation() throws Exception {
        ReasoningBank bank = new ReasoningBank();
        bank.add(new ReasoningUnit("u1", "k", "e", "f", "x",
                0.9, 0L, 0L, 0L, Instant.now()));
        BankRecallMiddleware recall = new BankRecallMiddleware(bank);
        SelfEvalMiddleware mw = new SelfEvalMiddleware(
                bank, recall, SelfEvalClassifier.heuristic(), false);
        mw.wrapToolCall(successTool("read_file"), Map.of(),
                AgentState.empty(), null);
        assertEquals(0, mw.totalEvaluations());
        assertEquals(0, bank.get("u1").orElseThrow().okCount());
    }

    // -----------------------------------------------------------------
    //  Confidence-weighted ranking integration
    // -----------------------------------------------------------------

    @Test
    void recallRankingPrefersHighConfidenceHighUtility() {
        // Two units, same utility, but one has been
        // observed many times and the other has never
        // been observed. The confidence * utility
        // product should rank the observed one higher.
        ReasoningBank bank = new ReasoningBank();
        bank.add(new ReasoningUnit("proven", "k", "e", "f", "x",
                0.8, 0L, 9L, 0L, Instant.now()));   // confidence 0.9
        bank.add(new ReasoningUnit("novel", "k", "e", "f", "x",
                0.8, 0L, 0L, 0L, Instant.now()));    // confidence 0.0
        BankRecallMiddleware recall = new BankRecallMiddleware(bank);
        List<ReasoningUnit> top = recall.recallAllKinds();
        assertFalse(top.isEmpty());
        assertEquals("proven", top.get(0).id(),
                "high-confidence high-utility unit should rank first");
    }
}
