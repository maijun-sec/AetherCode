package org.aethercode.memory;

import org.aethercode.memory.MemoryCriticAgent.Decision;
import org.aethercode.memory.MemoryCriticAgent.Verdict;
import org.aethercode.memory.ProceduralMemory.Procedure;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.*;

class MemoryCriticAgentTest {

    @Test
    void highScoreKeeps() {
        MemoryCriticAgent c = new MemoryCriticAgent(rec -> 0.8, 0.3, 0.1);
        Verdict v = c.evaluate(new Procedure("a", "n", "d", null, null, 0), "a");
        assertEquals(Decision.KEEP, v.decision());
        assertEquals(0.8, v.score());
    }

    @Test
    void midScoreMerges() {
        MemoryCriticAgent c = new MemoryCriticAgent(rec -> 0.2, 0.3, 0.1);
        Verdict v = c.evaluate(new Procedure("a", "n", "d", null, null, 0), "a");
        assertEquals(Decision.MERGE, v.decision());
    }

    @Test
    void lowScorePrunes() {
        MemoryCriticAgent c = new MemoryCriticAgent(rec -> 0.05, 0.3, 0.1);
        Verdict v = c.evaluate(new Procedure("a", "n", "d", null, null, 0), "a");
        assertEquals(Decision.PRUNE, v.decision());
    }

    @Test
    void customScorerUsed() {
        ToDoubleFunction<Object> lengthScorer = rec -> {
            if (rec instanceof Procedure p) {
                return Math.min(1.0, p.description().length() / 100.0);
            }
            return 0.0;
        };
        MemoryCriticAgent c = new MemoryCriticAgent(lengthScorer, 0.3, 0.1);
        Procedure longProc = new Procedure("a", "n", "a".repeat(80), null, null, 0);
        Verdict v = c.evaluate(longProc, "a");
        assertEquals(Decision.KEEP, v.decision());
    }

    @Test
    void batchEvaluate() {
        MemoryCriticAgent c = new MemoryCriticAgent(rec -> 0.5, 0.3, 0.1);
        List<Procedure> procs = List.of(
            new Procedure("a", "n", "d", null, null, 0),
            new Procedure("b", "n", "d", null, null, 0)
        );
        List<Verdict> vs = c.evaluateBatch(procs, Procedure::id);
        assertEquals(2, vs.size());
        assertEquals("a", vs.get(0).id());
        assertEquals("b", vs.get(1).id());
    }

    @Test
    void verdictHasReason() {
        MemoryCriticAgent c = new MemoryCriticAgent();
        Verdict v = c.evaluate(new Procedure("a", "n", "d", null, null, 0), "a");
        assertNotNull(v.reason());
        assertFalse(v.reason().isEmpty());
    }

    @Test
    void nullScorerRejected() {
        assertThrows(NullPointerException.class, () -> new MemoryCriticAgent(null, 0.3, 0.1));
    }

    @Test
    void decisionEnumValues() {
        assertEquals(3, Decision.values().length);
    }
}
