package org.aethercode.core.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalRequestTest {

    @Test
    void safetyCategory_isFlagged() {
        var req = new EvalRequest("A8.1.1", "A8", "x", "x", "msg",
                Map.of(), List.of(), Map.of(), "llm-judge",
                List.of("safety", "prompt-injection"), 1);
        assertTrue(req.isSafetyTest());
    }

    @Test
    void safetyTag_isFlagged() {
        var req = new EvalRequest("A9.1.1", "A9", "x", "x", "msg",
                Map.of(), List.of(), Map.of(), "deterministic",
                List.of("safety-related"), 1);
        assertTrue(req.isSafetyTest());
    }

    @Test
    void nonSafetyCategory_isNotFlagged() {
        var req = new EvalRequest("A1.1.1", "A1", "x", "x", "msg",
                Map.of(), List.of(), Map.of(), "deterministic",
                List.of("smoke"), 1);
        assertFalse(req.isSafetyTest());
    }

    @Test
    void memoryCategory_isFlagged() {
        var req = new EvalRequest("A3.1.1", "A3", "x", "x", "msg",
                Map.of(), List.of(), Map.of(), "llm-judge",
                List.of(), 1);
        assertTrue(req.isMemoryTest());
    }

    @Test
    void scoringMethod_matches() {
        var req = new EvalRequest("A1.1.1", "A1", "x", "x", "msg",
                Map.of(), List.of(), Map.of(), "deterministic",
                List.of(), 1);
        assertTrue(req.isScoringMethod("deterministic"));
        assertFalse(req.isScoringMethod("llm-judge"));
    }

    @Test
    void hasTag_isNullSafe() {
        var req = new EvalRequest("A1.1.1", "A1", "x", "x", "msg",
                Map.of(), List.of(), Map.of(), "deterministic",
                null, 1);
        assertFalse(req.hasTag("anything"));
        assertFalse(req.isSafetyTest());  // null tags + non-A8 = not safety
        assertFalse(req.isMemoryTest());
    }
}