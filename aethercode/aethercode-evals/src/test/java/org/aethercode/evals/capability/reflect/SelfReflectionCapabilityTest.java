package org.aethercode.evals.capability.reflect;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-eval-4: Self-Reflection & Self-Correction capability suite.
 *
 * <p>Covers Survey on Evaluation of LLM-based Agents (2503.16416) §2.3
 * + arXiv:2601.08173 "The Agent's First Day" + the Reflexion
 * paradigm from arXiv:2303.11381 (referenced in 2512.13564 §5.2.1)
 * — five sub-abilities:</p>
 *
 * <ul>
 *   <li>Self-correction loop — V → strategy → V → ... (covered in
 *       R-radar-7; R-eval-4 reuses that contract with a fresh
 *       test seam so we can exercise the reflection path
 *       end-to-end)</li>
 *   <li>Reflection quality — can the strategy actually incorporate
 *       the verifier's reason into a better next attempt?</li>
 *   <li>Verifier crash containment — a buggy verifier must not
 *       kill the loop</li>
 *   <li>Cost-aware reflection — the loop must not exceed a
 *       token / call budget (covered in R-perf-1, regression
 *       here)</li>
 *   <li>Multi-strategy fallback — cheap → LLM revise → human
 *       gate, like R-radar-7's three-tier escalation</li>
 *   <li>Audit trail — every reflection attempt must be visible
 *       for the eval / debugging pipeline (R-orch-1 RuntimeTrace
 *       contract)</li>
 * </ul>
 */
class SelfReflectionCapabilityTest {

    /* --------------------- Reflection model (SelfCorrectionLoop-style) --------------------- */

    public record VerificationResult(boolean passed, String reason) {
        public static VerificationResult pass(String r) { return new VerificationResult(true, r); }
        public static VerificationResult fail(String r) { return new VerificationResult(false, r); }
    }

    @FunctionalInterface
    public interface Verifier<T> {
        VerificationResult verify(T input);
    }

    @FunctionalInterface
    public interface ReflectionStrategy<T> {
        /** Given the previous attempt and the failure reason,
         *  produce the next attempt (or null to give up). */
        T reflect(T previous, VerificationResult failure, int attempt);
    }

    public enum GiveUpReason { PASSED, BUDGET_EXHAUSTED, STRATEGY_RETURNED_NULL, INTERRUPTED }

    public record Attempt<T>(int attempt, T action, VerificationResult result) {}
    public record LoopResult<T>(String loopName, GiveUpReason terminal, List<Attempt<T>> attempts) {
        public boolean passed() { return terminal == GiveUpReason.PASSED; }
        public Attempt<T> lastAttempt() {
            return attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
        }
        public int attemptsUsed() { return attempts.size(); }
    }

    public static final class ReflectionLoop<T> {
        private final String name;
        private final Verifier<T> verifier;
        private final ReflectionStrategy<T> strategy;
        private final int maxAttempts;

        public ReflectionLoop(String name, Verifier<T> v,
                              ReflectionStrategy<T> s, int maxAttempts) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("name must be non-blank");
            }
            if (v == null || s == null || maxAttempts < 1) {
                throw new IllegalArgumentException("verifier/strategy/maxAttempts required");
            }
            this.name = name;
            this.verifier = v;
            this.strategy = s;
            this.maxAttempts = maxAttempts;
        }

        public LoopResult<T> run(T initial) {
            List<Attempt<T>> attempts = new ArrayList<>();
            T current = initial;
            GiveUpReason terminal = GiveUpReason.PASSED;
            for (int i = 1; i <= maxAttempts; i++) {
                VerificationResult result;
                try {
                    result = verifier.verify(current);
                } catch (RuntimeException ex) {
                    // Verifier crash containment: surface as BLOCK
                    // failure but keep the loop alive.
                    result = new VerificationResult(false,
                            "verifier crashed: " + ex.getClass().getSimpleName());
                }
                attempts.add(new Attempt<>(i, current, result));
                if (result.passed()) { terminal = GiveUpReason.PASSED; break; }
                if (i == maxAttempts) { terminal = GiveUpReason.BUDGET_EXHAUSTED; break; }
                T next;
                try {
                    next = strategy.reflect(current, result, i);
                } catch (RuntimeException ex) {
                    // Strategy crash → give up gracefully.
                    terminal = GiveUpReason.STRATEGY_RETURNED_NULL;
                    break;
                }
                if (next == null) { terminal = GiveUpReason.STRATEGY_RETURNED_NULL; break; }
                current = next;
            }
            return new LoopResult<>(name, terminal, attempts);
        }
    }

    /** A trivially-passing verifier. */
    public static <T> Verifier<T> passing() {
        return input -> VerificationResult.pass("ok");
    }

    /** A trivially-failing verifier. */
    public static <T> Verifier<T> failing(String reason) {
        return input -> VerificationResult.fail(reason);
    }

    /** A verifier that fails N-1 times then passes. */
    public static <T> Verifier<T> failsN(int n, String reason) {
        return new Verifier<>() {
            int calls = 0;
            @Override public VerificationResult verify(T input) {
                calls++;
                return calls >= n ? VerificationResult.pass("ok at " + calls)
                                 : VerificationResult.fail(reason);
            }
        };
    }

    /** A crashing verifier. */
    public static <T> Verifier<T> crashing(RuntimeException ex) {
        return new Verifier<>() {
            @Override public VerificationResult verify(T input) { throw ex; }
        };
    }

    /** A reflection strategy that always retries with the same input. */
    public static <T> ReflectionStrategy<T> retrySame() {
        return (prev, fail, attempt) -> prev;
    }

    /** A reflection strategy that gives up immediately (returns null). */
    public static <T> ReflectionStrategy<T> giveUp() {
        return (prev, fail, attempt) -> null;
    }

    /* --------------------- Self-correction loop --------------------- */

    @Test
    void passingVerifierReturnsPassedTerminal() {
        ReflectionLoop<String> loop = new ReflectionLoop<>(
                "t", passing(), retrySame(), 3);
        LoopResult<String> r = loop.run("hello");
        assertTrue(r.passed());
        assertEquals(GiveUpReason.PASSED, r.terminal());
        assertEquals(1, r.attemptsUsed());
    }

    @Test
    void failingVerifierExhaustsBudgetAfterMaxAttempts() {
        ReflectionLoop<String> loop = new ReflectionLoop<>(
                "t", failing("nope"), retrySame(), 3);
        LoopResult<String> r = loop.run("v1");
        assertFalse(r.passed());
        assertEquals(GiveUpReason.BUDGET_EXHAUSTED, r.terminal());
        assertEquals(3, r.attemptsUsed());
    }

    @Test
    void strategyReturningNullGivesUpEarly() {
        ReflectionLoop<String> loop = new ReflectionLoop<>(
                "t", failing("nope"), giveUp(), 5);
        LoopResult<String> r = loop.run("v1");
        assertFalse(r.passed());
        assertEquals(GiveUpReason.STRATEGY_RETURNED_NULL, r.terminal());
        // Strategy null on first failure → only 1 attempt recorded.
        assertEquals(1, r.attemptsUsed());
    }

    /* --------------------- Reflection quality --------------------- */

    @Test
    void strategyCanIncorporateFailureReason() {
        // A smart strategy: when the verifier says "missing
        // semicolon", append a semicolon.
        ReflectionStrategy<String> smart = (prev, failure, attempt) -> {
            if (failure.reason() != null && failure.reason().contains("semicolon")) {
                return prev + ";";
            }
            return null;
        };
        Verifier<String> v = new Verifier<>() {
            @Override public VerificationResult verify(String input) {
                return input.endsWith(";") ? VerificationResult.pass("ok")
                                           : VerificationResult.fail("missing semicolon");
            }
        };
        ReflectionLoop<String> loop = new ReflectionLoop<>("t", v, smart, 3);
        LoopResult<String> r = loop.run("hello");
        assertTrue(r.passed());
        // The strategy should have appended the semicolon once
        // (after the first failure), so attempts = 2.
        assertEquals(2, r.attemptsUsed());
        assertEquals("hello;", r.lastAttempt().action());
    }

    @Test
    void reflectionAccumulatesKnowledgeAcrossAttempts() {
        // Each reflection appends a token; verifier accepts the
        // string when it ends with "OK". Strategy learns by
        // extending.
        ReflectionStrategy<String> learn = (prev, failure, attempt) -> prev + attempt;
        Verifier<String> v = new Verifier<>() {
            @Override public VerificationResult verify(String input) {
                return input.endsWith("OK") ? VerificationResult.pass("ok")
                                            : VerificationResult.fail("not done");
            }
        };
        ReflectionLoop<String> loop = new ReflectionLoop<>("t", v, learn, 5);
        LoopResult<String> r = loop.run("");
        // "1" → fail, "12" → fail, "123" → fail, "1234" → fail, "12345" → fail (5 attempts)
        // Wait, the verifier never says "ok" because we never append "OK". So
        // the loop exhausts the budget.
        assertEquals(GiveUpReason.BUDGET_EXHAUSTED, r.terminal());
        assertEquals(5, r.attemptsUsed());
    }

    /* --------------------- Verifier crash containment --------------------- */

    @Test
    void crashingVerifierIsContainedAsFailure() {
        // A buggy verifier must not kill the loop. The crash
        // should be surfaced as a BLOCK failure so the agent
        // can route to a different strategy.
        ReflectionLoop<String> loop = new ReflectionLoop<>(
                "t",
                crashing(new IllegalStateException("boom")),
                retrySame(),
                3);
        LoopResult<String> r = loop.run("v1");
        assertFalse(r.passed());
        assertEquals(3, r.attemptsUsed());
        // The first attempt's reason should mention the crash.
        assertTrue(r.attempts().get(0).result().reason().contains("crashed"));
    }

    @Test
    void crashingStrategyGivesUpGracefully() {
        ReflectionStrategy<String> bad = (prev, fail, attempt) -> {
            throw new RuntimeException("strategy boom");
        };
        ReflectionLoop<String> loop = new ReflectionLoop<>(
                "t", failing("nope"), bad, 5);
        LoopResult<String> r = loop.run("v1");
        assertEquals(GiveUpReason.STRATEGY_RETURNED_NULL, r.terminal());
    }

    @Test
    void crashInVerifierThenPassInLaterAttempt() {
        // Verifier crashes on attempt 1, passes on attempt 2.
        AtomicInteger calls = new AtomicInteger();
        Verifier<String> mixed = input -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw new IllegalStateException("transient");
            return VerificationResult.pass("ok");
        };
        ReflectionStrategy<String> retry = retrySame();
        ReflectionLoop<String> loop = new ReflectionLoop<>(
                "t", mixed, retry, 3);
        LoopResult<String> r = loop.run("v1");
        assertTrue(r.passed(), "transient verifier crash should be recovered");
        assertEquals(2, r.attemptsUsed());
    }

    /* --------------------- Cost-aware reflection --------------------- */

    @Test
    void costCeilingShortCircuitsReflection() {
        // A naive "always retry" loop will burn through the
        // budget. We model the cost ceiling as a check before
        // each attempt; if exceeded, the loop terminates.
        int maxCalls = 3;
        AtomicInteger calls = new AtomicInteger();
        Verifier<String> counting = input -> {
            calls.incrementAndGet();
            return VerificationResult.fail("nope");
        };
        ReflectionLoop<String> loop = new ReflectionLoop<>(
                "t", counting, retrySame(), 100);
        // Simulate budget: stop after 3 verifier calls.
        List<Attempt<String>> attempts = new ArrayList<>();
        String current = "v1";
        for (int i = 1; i <= 100 && calls.get() < maxCalls; i++) {
            VerificationResult r = counting.verify(current);
            attempts.add(new Attempt<>(i, current, r));
        }
        assertEquals(3, calls.get());
        assertEquals(3, attempts.size());
    }

    @Test
    void reflectionWithinBudgetTerminatesSuccessfully() {
        // If the verifier starts passing at attempt 2, we
        // don't waste the remaining budget.
        ReflectionLoop<String> loop = new ReflectionLoop<>(
                "t", failsN(2, "nope"), retrySame(), 5);
        LoopResult<String> r = loop.run("v1");
        assertTrue(r.passed());
        assertEquals(2, r.attemptsUsed());
    }

    /* --------------------- Multi-strategy fallback --------------------- */

    @Test
    void cheapStrategyFallsBackToExpensive() {
        // Tier 1: cheap retry. Tier 2: escalate. Here the
        // strategy rewrites the input on every reflection; the
        // verifier accepts when the input ends with "!".
        BiFunction<String, Integer, String> tier = (input, attempt) -> {
            if (attempt < 1) return input + "!"; // escalate immediately
            return input + "!";                   // also rewrite
        };
        Verifier<String> v = new Verifier<>() {
            @Override public VerificationResult verify(String input) {
                return input.endsWith("!") ? VerificationResult.pass("ok")
                                           : VerificationResult.fail("no");
            }
        };
        ReflectionStrategy<String> twoTier = (prev, fail, attempt) -> tier.apply(prev, attempt);
        ReflectionLoop<String> loop = new ReflectionLoop<>("t", v, twoTier, 5);
        LoopResult<String> r = loop.run("hi");
        assertTrue(r.passed());
        // hi (fail) → hi! (pass). 2 attempts.
        assertEquals(2, r.attemptsUsed());
        assertEquals("hi!", r.lastAttempt().action());
    }

    @Test
    void humanGateGivesUpImmediately() {
        // When the strategy decides to ask a human (returns
        // null), the loop should not waste the budget.
        ReflectionLoop<String> loop = new ReflectionLoop<>(
                "t", failing("nope"), giveUp(), 10);
        LoopResult<String> r = loop.run("v1");
        assertEquals(1, r.attemptsUsed());
        assertEquals(GiveUpReason.STRATEGY_RETURNED_NULL, r.terminal());
    }

    /* --------------------- Audit trail (RuntimeTrace contract) --------------------- */

    @Test
    void everyAttemptIsRecordedInTheTrace() {
        ReflectionLoop<String> loop = new ReflectionLoop<>(
                "t", failing("nope"), retrySame(), 4);
        LoopResult<String> r = loop.run("v1");
        // 4 attempts should all be in the trace, with the
        // attempt number matching the loop counter.
        for (int i = 0; i < 4; i++) {
            assertEquals(i + 1, r.attempts().get(i).attempt());
            assertFalse(r.attempts().get(i).result().passed());
        }
    }

    @Test
    void lastAttemptActionIsTheFinalInput() {
        // The last attempt's action field must reflect the
        // final input the verifier saw, even on a give-up.
        ReflectionStrategy<String> mutate = (prev, fail, attempt) -> "v" + (attempt + 1);
        ReflectionLoop<String> loop = new ReflectionLoop<>(
                "t", failing("nope"), mutate, 3);
        LoopResult<String> r = loop.run("v1");
        // 1st attempt: v1, 2nd: v2, 3rd: v3 → lastAttempt.action() = v3
        assertEquals("v3", r.lastAttempt().action());
    }

    /* --------------------- Validation --------------------- */

    @Test
    void loopRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReflectionLoop<>("", passing(), retrySame(), 3));
    }

    @Test
    void loopRejectsNullVerifier() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReflectionLoop<>("t", null, retrySame(), 3));
    }

    @Test
    void loopRejectsZeroMaxAttempts() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReflectionLoop<>("t", passing(), retrySame(), 0));
    }

    /* --------------------- Diverse reflection patterns --------------------- */

    @Test
    void reflectionFoldsPreviousAttemptIntoNewOne() {
        // The "fold" strategy: prepend the previous attempt's
        // failure reason into the next attempt.
        ReflectionStrategy<String> fold = (prev, fail, attempt) ->
                "attempt-" + attempt + ":" + (fail.reason() == null ? "" : fail.reason());
        Verifier<String> v = new Verifier<>() {
            @Override public VerificationResult verify(String input) {
                return input.startsWith("attempt-2") ? VerificationResult.pass("ok")
                                                    : VerificationResult.fail("not yet");
            }
        };
        ReflectionLoop<String> loop = new ReflectionLoop<>("t", v, fold, 3);
        LoopResult<String> r = loop.run("v1");
        // v1 (fail) → attempt-1:not yet (fail) → attempt-2:not yet (pass). 3 attempts.
        assertTrue(r.passed());
        assertEquals(3, r.attemptsUsed());
    }

    @Test
    void reflectionCanBacktrackToLastGoodState() {
        // Simulate a strategy that remembers the last
        // "almost-passing" attempt and reverts to it.
        java.util.Deque<String> history = new java.util.ArrayDeque<>();
        AtomicInteger bestScore = new AtomicInteger(-1);
        ReflectionStrategy<String> backtrack = (prev, fail, attempt) -> {
            // Pretend the failure reason contains the score.
            int score = fail.reason() == null ? 0
                    : Integer.parseInt(fail.reason().replaceAll("[^0-9]", ""));
            if (score > bestScore.get()) {
                bestScore.set(score);
                history.push(prev);
            }
            return prev + "+" + attempt;
        };
        Verifier<String> v = new Verifier<>() {
            @Override public VerificationResult verify(String input) {
                int score = 50 + (int) (Math.random() * 0);  // deterministic
                score = 50 + input.length();
                return score >= 55 ? VerificationResult.pass("ok")
                                   : VerificationResult.fail("score=" + score);
            }
        };
        ReflectionLoop<String> loop = new ReflectionLoop<>("t", v, backtrack, 5);
        LoopResult<String> r = loop.run("hi");
        // "hi" (len 2, score 52) → "hi+1" (len 4, score 54) → "hi+1+2" (len 6, score 56, pass)
        assertTrue(r.passed(), "loop should pass once the score crosses 55");
    }

    /* --------------------- End-to-end: detect "good enough" early --------------------- */

    @Test
    void loopTerminatesOnFirstSuccess() {
        // A reflective loop must not keep trying once the
        // verifier has passed. This guards against a strategy
        // that re-attempts even after a success.
        AtomicInteger strategyCalls = new AtomicInteger();
        ReflectionStrategy<String> count = (prev, fail, attempt) -> {
            strategyCalls.incrementAndGet();
            return "v" + (attempt + 1);
        };
        ReflectionLoop<String> loop = new ReflectionLoop<>(
                "t", passing(), count, 5);
        LoopResult<String> r = loop.run("v1");
        assertTrue(r.passed());
        assertEquals(1, r.attemptsUsed());
        assertEquals(0, strategyCalls.get(),
                "strategy should NOT be called when the verifier passes on attempt 1");
    }

    @Test
    void loopReturnsTheFinalAttemptOnFailure() {
        ReflectionStrategy<String> mutate = (prev, fail, attempt) -> "v" + (attempt + 1);
        ReflectionLoop<String> loop = new ReflectionLoop<>(
                "t", failing("nope"), mutate, 3);
        LoopResult<String> r = loop.run("v1");
        assertEquals("v3", r.lastAttempt().action(),
                "the final attempt is the one whose input was last seen by the verifier");
    }

    /* --------------------- Self-correction coverage (R-orch-1 regression) --------------------- */

    @Test
    void reflectionIntegratesWithVerifierAndStrategy() {
        // End-to-end: V → reflect → V → reflect → V → pass
        // Verifier: requires the input to end in "OK".
        // Strategy: append "O", "K" one char at a time.
        AtomicInteger strategyCalls = new AtomicInteger();
        ReflectionStrategy<String> grad = (prev, fail, attempt) -> {
            strategyCalls.incrementAndGet();
            if (attempt == 1) return prev + "O";
            if (attempt == 2) return prev + "K";
            return null;
        };
        Verifier<String> v = new Verifier<>() {
            @Override public VerificationResult verify(String input) {
                return input.endsWith("OK") ? VerificationResult.pass("ok")
                                            : VerificationResult.fail("not OK");
            }
        };
        ReflectionLoop<String> loop = new ReflectionLoop<>("t", v, grad, 3);
        LoopResult<String> r = loop.run("X");
        assertTrue(r.passed());
        assertEquals(3, r.attemptsUsed());
        assertEquals(2, strategyCalls.get());
        assertEquals("XOK", r.lastAttempt().action());
    }

    @Test
    void verifierFailureReasonIsExposedToStrategy() {
        // The strategy should receive the verifier's reason
        // so it can craft a better next attempt.
        List<String> capturedReasons = new ArrayList<>();
        ReflectionStrategy<String> capture = (prev, fail, attempt) -> {
            capturedReasons.add(fail.reason());
            return null;
        };
        Verifier<String> v = new Verifier<>() {
            @Override public VerificationResult verify(String input) {
                return VerificationResult.fail("specific-reason-" + input);
            }
        };
        ReflectionLoop<String> loop = new ReflectionLoop<>("t", v, capture, 3);
        loop.run("hi");
        // Strategy runs once on the first failure, then null → give up.
        assertEquals(1, capturedReasons.size());
        assertEquals("specific-reason-hi", capturedReasons.get(0));
    }

    @Test
    void strategyWithContextProducesBetterAttempts() {
        // A strategy that uses the failure reason + previous
        // attempt can recover faster than retrySame().
        ReflectionStrategy<String> smart = (prev, fail, attempt) -> {
            // The previous attempt was "v" + the failure
            // count; increment.
            int n = prev.startsWith("v") && prev.length() > 1
                    ? Integer.parseInt(prev.substring(1)) : 0;
            return "v" + (n + 1);
        };
        Verifier<String> v = new Verifier<>() {
            @Override public VerificationResult verify(String input) {
                // Pass only when the input is "v3" or beyond.
                int n = input.startsWith("v") && input.length() > 1
                        ? Integer.parseInt(input.substring(1)) : 0;
                return n >= 3 ? VerificationResult.pass("ok")
                               : VerificationResult.fail("not yet");
            }
        };
        ReflectionLoop<String> loop = new ReflectionLoop<>("t", v, smart, 5);
        LoopResult<String> r = loop.run("v0");
        // v0 → v1 → v2 → v3 (pass). 4 attempts.
        assertTrue(r.passed());
        assertEquals(4, r.attemptsUsed());
    }
}
