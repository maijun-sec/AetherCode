package org.aethercode.evals.selfcorrect;

import org.aethercode.evals.selfcorrect.CorrectionStrategy.GiveUpReason;
import org.aethercode.evals.selfcorrect.SelfCorrectionLoop.Attempt;
import org.aethercode.evals.selfcorrect.SelfCorrectionLoop.LoopResult;
import org.aethercode.evals.verifier.Verifier;
import org.aethercode.evals.verifier.Verifier.Severity;
import org.aethercode.evals.verifier.Verifier.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SelfCorrectionLoop}.
 */
class SelfCorrectionLoopTest {

    /* ----------------------- fixtures ----------------------- */

    /** Verifier that fails the first {@code failCount} inputs, then passes. */
    private static Verifier<String> failsNThenPasses(int failCount) {
        return new Verifier<>() {
            int calls = 0;
            @Override public String name() { return "fails-" + failCount; }
            @Override public VerificationResult verify(String input) {
                calls++;
                if (calls <= failCount) {
                    return VerificationResult.fail(Severity.BLOCK, "fail #" + calls,
                            Map.of("verifier", name(), "call", calls));
                }
                return VerificationResult.pass("ok at " + calls,
                        Map.of("verifier", name(), "call", calls));
            }
        };
    }

    /** Verifier that always passes. */
    private static Verifier<String> alwaysPass() {
        return new Verifier<>() {
            @Override public String name() { return "always-pass"; }
            @Override public VerificationResult verify(String input) {
                return VerificationResult.pass("ok",
                        Map.of("verifier", name()));
            }
        };
    }

    /** Verifier that always fails. */
    private static Verifier<String> alwaysFail(String reason) {
        return new Verifier<>() {
            @Override public String name() { return "always-fail"; }
            @Override public VerificationResult verify(String input) {
                return VerificationResult.fail(Severity.BLOCK, reason,
                        Map.of("verifier", name()));
            }
        };
    }

    /** Strategy that calls a function each attempt, returns null after {@code nullAfter}. */
    private static <T> CorrectionStrategy<T> strategy(java.util.function.BiFunction<Integer, T, T> fn,
                                                       int nullAfter) {
        return new CorrectionStrategy<>() {
            int calls = 0;
            @Override public T next(T previous, VerificationResult failure, int attempt) {
                calls++;
                if (calls > nullAfter) return null;
                return fn.apply(attempt, previous);
            }
        };
    }

    /* ----------------------- constructor ----------------------- */

    @Test
    void constructorRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new SelfCorrectionLoop<>("", alwaysPass(), RetryStrategy.giveUpImmediately(), 3));
    }

    @Test
    void constructorRejectsNullVerifier() {
        assertThrows(IllegalArgumentException.class,
                () -> new SelfCorrectionLoop<>("loop", null, RetryStrategy.giveUpImmediately(), 3));
    }

    @Test
    void constructorRejectsNullStrategy() {
        assertThrows(IllegalArgumentException.class,
                () -> new SelfCorrectionLoop<>("loop", alwaysPass(), null, 3));
    }

    @Test
    void constructorRejectsZeroMaxAttempts() {
        assertThrows(IllegalArgumentException.class,
                () -> new SelfCorrectionLoop<>("loop", alwaysPass(), RetryStrategy.giveUpImmediately(), 0));
    }

    /* ----------------------- pass on first try ----------------------- */

    @Test
    void passesOnFirstAttempt() {
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>(
                "loop", alwaysPass(), RetryStrategy.retrySame(), 3);
        LoopResult<String> r = loop.run("hello");
        assertTrue(r.passed());
        assertEquals(GiveUpReason.PASSED, r.terminal());
        assertEquals(1, r.attemptsUsed());
    }

    /* ----------------------- retry then pass ----------------------- */

    @Test
    void passesOnSecondAttempt() {
        Verifier<String> v = failsNThenPasses(1);
        // Strategy: first call returns "v2", second call returns null
        // (give up).  Since v passes on call 2, we never reach the null.
        CorrectionStrategy<String> s = strategy((attempt, prev) -> "v" + (attempt + 1), 1);
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>("loop", v, s, 5);
        LoopResult<String> r = loop.run("v1");
        assertTrue(r.passed());
        assertEquals(2, r.attemptsUsed());
        assertEquals("v2", r.lastAttempt().action());
        assertEquals("ok at 2", r.lastAttempt().result().reason());
    }

    @Test
    void passesOnThirdAttempt() {
        Verifier<String> v = failsNThenPasses(2);
        CorrectionStrategy<String> s = strategy((attempt, prev) -> "v" + (attempt + 1), 5);
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>("loop", v, s, 5);
        LoopResult<String> r = loop.run("v1");
        assertTrue(r.passed());
        assertEquals(3, r.attemptsUsed());
    }

    /* ----------------------- budget exhausted ----------------------- */

    @Test
    void budgetExhaustedWhenStrategyKeepsFailing() {
        // Strategy returns "next" forever but verifier always fails.
        // Budget = 3, so we attempt 3 times then stop.
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>(
                "loop", alwaysFail("nope"),
                strategy((attempt, prev) -> "v" + (attempt + 1), 100),
                3);
        LoopResult<String> r = loop.run("v1");
        assertFalse(r.passed());
        assertEquals(GiveUpReason.BUDGET_EXHAUSTED, r.terminal());
        assertEquals(3, r.attemptsUsed());
        assertEquals(3, r.maxAttempts());
    }

    @Test
    void budgetExhaustedOnFirstAttemptWhenMaxAttemptsIsOne() {
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>(
                "loop", alwaysFail("nope"),
                strategy((attempt, prev) -> "v" + (attempt + 1), 5),
                1);
        LoopResult<String> r = loop.run("v1");
        assertFalse(r.passed());
        assertEquals(GiveUpReason.BUDGET_EXHAUSTED, r.terminal());
        assertEquals(1, r.attemptsUsed());
    }

    /* ----------------------- strategy gives up ----------------------- */

    @Test
    void strategyGivesUpOnSecondAttempt() {
        // First call: returns "v2". Verifier fails again. Second call: null.
        // Loop terminates with STRATEGY_RETURNED_NULL.
        AtomicInteger calls = new AtomicInteger();
        CorrectionStrategy<String> s = (prev, fail, attempt) -> {
            int n = calls.incrementAndGet();
            return n == 1 ? "v2" : null;
        };
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>(
                "loop", alwaysFail("nope"), s, 5);
        LoopResult<String> r = loop.run("v1");
        assertFalse(r.passed());
        assertEquals(GiveUpReason.STRATEGY_RETURNED_NULL, r.terminal());
        assertEquals(2, r.attemptsUsed());
    }

    @Test
    void strategyCrashingIsContained() {
        // Strategy that throws — loop should not crash; it should
        // record an extra attempt with BLOCK + the strategy's
        // exception, then terminate.
        CorrectionStrategy<String> s = (prev, fail, attempt) -> {
            throw new RuntimeException("strategy boom");
        };
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>(
                "loop", alwaysFail("nope"), s, 5);
        LoopResult<String> r = loop.run("v1");
        assertFalse(r.passed());
        assertEquals(GiveUpReason.STRATEGY_RETURNED_NULL, r.terminal());
    }

    @Test
    void verifierCrashingIsContained() {
        Verifier<String> v = new Verifier<>() {
            @Override public String name() { return "crashy"; }
            @Override public VerificationResult verify(String input) {
                throw new RuntimeException("verifier boom");
            }
        };
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>(
                "loop", v, RetryStrategy.retrySame(), 3);
        LoopResult<String> r = loop.run("v1");
        assertFalse(r.passed());
        // The loop survived the crash and eventually exhausted the budget.
        assertEquals(GiveUpReason.BUDGET_EXHAUSTED, r.terminal());
        assertEquals(3, r.attemptsUsed());
    }

    /* ----------------------- history ----------------------- */

    @Test
    void attemptsHistoryIsComplete() {
        Verifier<String> v = failsNThenPasses(2);
        CorrectionStrategy<String> s = strategy((attempt, prev) -> "v" + (attempt + 1), 5);
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>("loop", v, s, 5);
        LoopResult<String> r = loop.run("v1");
        assertEquals(3, r.attempts().size());
        assertEquals("v1", r.attempts().get(0).action());
        assertEquals("v2", r.attempts().get(1).action());
        assertEquals("v3", r.attempts().get(2).action());
        assertEquals(1, r.attempts().get(0).attempt());
        assertEquals(2, r.attempts().get(1).attempt());
        assertEquals(3, r.attempts().get(2).attempt());
        assertFalse(r.attempts().get(0).result().passed());
        assertFalse(r.attempts().get(1).result().passed());
        assertTrue(r.attempts().get(2).result().passed());
    }

    @Test
    void failureTrailSummarises() {
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>(
                "loop", alwaysFail("boom"),
                strategy((attempt, prev) -> "v" + (attempt + 1), 5),
                3);
        LoopResult<String> r = loop.run("v1");
        String trail = r.failureTrail();
        assertTrue(trail.contains("attempt 1: boom"), trail);
        assertTrue(trail.contains("attempt 2: boom"), trail);
        assertTrue(trail.contains("attempt 3: boom"), trail);
        // 3 occurrences separated by " | "
        assertEquals(2, trail.split(" \\| ").length - 1);
    }

    @Test
    void lastAttemptReturnsTheFinalAction() {
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>(
                "loop", failsNThenPasses(1),
                strategy((attempt, prev) -> "v" + (attempt + 1), 5),
                5);
        LoopResult<String> r = loop.run("v1");
        assertNotNull(r.lastAttempt());
        assertEquals("v2", r.lastAttempt().action());
    }

    @Test
    void summaryExposesHeadlineFields() {
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>(
                "loop", alwaysFail("nope"),
                strategy((attempt, prev) -> "v" + (attempt + 1), 5),
                2);
        LoopResult<String> r = loop.run("v1");
        java.util.Map<String, Object> s = r.summary();
        assertEquals("loop", s.get("loop"));
        assertEquals("always-fail", s.get("verifier"));
        assertEquals(GiveUpReason.BUDGET_EXHAUSTED, s.get("terminal"));
        assertEquals(false, s.get("passed"));
        assertEquals(2, s.get("attempts_used"));
        assertEquals(2, s.get("max_attempts"));
    }

    /* ----------------------- accessors ----------------------- */

    @Test
    void nameVerifierStrategyAccessors() {
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>(
                "loop", alwaysPass(), RetryStrategy.retrySame(), 5);
        assertEquals("loop", loop.name());
        assertEquals("always-pass", loop.verifier().name());
        assertNotNull(loop.strategy());
        assertEquals(5, loop.maxAttempts());
    }

    /* ----------------------- attempt record ----------------------- */

    @Test
    void attemptRecordRejectsBadArgs() {
        assertThrows(IllegalArgumentException.class,
                () -> new Attempt<>(0, "x", VerificationResult.pass()));
        assertThrows(IllegalArgumentException.class,
                () -> new Attempt<>(1, null, VerificationResult.pass()));
        assertThrows(IllegalArgumentException.class,
                () -> new Attempt<>(1, "x", null));
    }
}
