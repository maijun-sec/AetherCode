package org.aethercode.evals.perf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CostCeiling}.
 */
class CostCeilingTest {

    @Test
    void builderDefaultsAreReasonable() {
        CostCeiling c = CostCeiling.builder().build();
        assertEquals(64, c.maxCalls());
        assertEquals(60_000L, c.maxMillis());
        assertEquals(1_000_000L, c.maxTokens());
    }

    @Test
    void builderOverloadAcceptsCustomLimits() {
        CostCeiling c = CostCeiling.builder()
                .maxCalls(3)
                .maxMillis(100L)
                .maxTokens(500L)
                .build();
        assertEquals(3, c.maxCalls());
        assertEquals(100L, c.maxMillis());
        assertEquals(500L, c.maxTokens());
    }

    @Test
    void constructorRejectsInvalidArgs() {
        assertThrows(IllegalArgumentException.class, () -> new CostCeiling(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CostCeiling(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new CostCeiling(1, 1, 0));
    }

    @Test
    void recordCallIncrementsCountersAndReturnsFalseUntilExceeded() {
        // exceeded() is `>=` semantics: as soon as the budget is fully
        // consumed the next call would push it over, so the call that
        // exactly hits the limit already counts as exceeded.
        CostCeiling c = CostCeiling.builder().maxCalls(3).maxMillis(60_000L).maxTokens(1000L).build();
        assertFalse(c.recordCall(10), "call 1 under budget");
        assertFalse(c.recordCall(10), "call 2 under budget");
        assertTrue(c.recordCall(10), "call 3 hits budget (>=)");
        assertTrue(c.recordCall(10), "call 4 over budget");
        assertEquals(4, c.calls());
        assertEquals(40L, c.tokens());
    }

    @Test
    void recordCallRejectsNegativeTokens() {
        CostCeiling c = CostCeiling.builder().maxCalls(5).maxMillis(60_000L).maxTokens(100L).build();
        assertThrows(IllegalArgumentException.class, () -> c.recordCall(-1));
    }

    @Test
    void exceededReturnsTrueOnCallOverrun() {
        CostCeiling c = CostCeiling.builder().maxCalls(2).maxMillis(60_000L).maxTokens(1000L).build();
        c.recordCall(1);
        assertFalse(c.exceeded());
        c.recordCall(1);
        c.recordCall(1);
        assertTrue(c.exceeded(), "calls > maxCalls triggers exceeded()");
    }

    @Test
    void exceededReturnsTrueOnTokenOverrun() {
        CostCeiling c = CostCeiling.builder().maxCalls(1000).maxMillis(60_000L).maxTokens(50L).build();
        c.recordCall(30);
        assertFalse(c.exceeded());
        c.recordCall(30);
        assertTrue(c.exceeded(), "tokens > maxTokens triggers exceeded()");
    }

    @Test
    void usageSnapshotReportsAllThreeAxes() {
        CostCeiling c = CostCeiling.builder().maxCalls(10).maxMillis(60_000L).maxTokens(100L).build();
        c.recordCall(15);
        c.recordCall(20);
        CostCeiling.Usage u = c.usage();
        assertEquals(2, u.calls());
        assertEquals(35L, u.tokens());
        assertEquals(10, u.maxCalls());
        assertEquals(100L, u.maxTokens());
        assertTrue(u.elapsedMillis() >= 0);
        assertFalse(u.anyExceeded());
    }

    @Test
    void usageFractionHelpersComputeRatios() {
        CostCeiling c = CostCeiling.builder().maxCalls(4).maxMillis(60_000L).maxTokens(100L).build();
        c.recordCall(25);
        CostCeiling.Usage u = c.usage();
        assertEquals(0.25, u.callFraction(), 1e-9);
        assertEquals(0.25, u.tokenFraction(), 1e-9);
        assertTrue(u.timeFraction() >= 0.0);
    }

    @Test
    void equalsAndHashCodeAreValueBased() {
        CostCeiling a = CostCeiling.builder().maxCalls(3).maxMillis(100L).maxTokens(50L).build();
        CostCeiling b = CostCeiling.builder().maxCalls(3).maxMillis(100L).maxTokens(50L).build();
        CostCeiling c = CostCeiling.builder().maxCalls(4).maxMillis(100L).maxTokens(50L).build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c));
    }
}
