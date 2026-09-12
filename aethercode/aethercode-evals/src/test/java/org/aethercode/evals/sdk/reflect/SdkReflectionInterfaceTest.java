package org.aethercode.evals.sdk.reflect;

import org.aethercode.orchestration.selfcorrect.CorrectionStrategy;
import org.aethercode.orchestration.selfcorrect.CorrectionStrategy.GiveUpReason;
import org.aethercode.orchestration.selfcorrect.RetryStrategy;
import org.aethercode.orchestration.selfcorrect.SelfCorrectionLoop;
import org.aethercode.orchestration.selfcorrect.SelfCorrectionLoop.LoopResult;
import org.aethercode.orchestration.verifier.HeuristicVerifier;
import org.aethercode.orchestration.verifier.Verifier;
import org.aethercode.orchestration.verifier.Verifier.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-sdk-8: AetherCode Reflection / Self-Correction Interface conformance.
 *
 * <p>Companion to {@code SelfReflectionCapabilityTest} (R-eval-4) and
 * the orchestration-suite's
 * {@code SelfCorrectionLoopTest} (R-orch-1). The capability suite
 * proves the reflection design on a self-contained model; the
 * orchestration suite proves the production-class unit-level
 * behaviour. This suite proves the actual
 * {@link SelfCorrectionLoop} + {@link CorrectionStrategy} + the
 * terminal {@link GiveUpReason} taxonomy that the
 * {@code AgentRuntime} composes with.</p>
 *
 * <p>Scope: 4 invariants the front-end / runtime / TUI depends on
 * when an agent is mid-self-correction.</p>
 */
class SdkReflectionInterfaceTest {

    @Test
    void selfCorrectionLoopPassesOnFirstAttempt() {
        Verifier<String> pass = new Verifier<>() {
            @Override public String name() { return "pass"; }
            @Override public VerificationResult verify(String s) { return VerificationResult.pass(); }
        };
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>(
                "first-try", pass, RetryStrategy.retrySame(), 3);
        LoopResult<String> r = loop.run("x");
        assertEquals(GiveUpReason.PASSED, r.terminal());
        assertEquals(1, r.attempts().size());
        assertTrue(r.attempts().get(0).result().passed());
    }

    @Test
    void selfCorrectionLoopGivesUpOnBudgetExhaustion() {
        Verifier<String> fail = new Verifier<>() {
            @Override public String name() { return "fail"; }
            @Override public VerificationResult verify(String s) {
                return VerificationResult.fail(Verifier.Severity.BLOCK, "no");
            }
        };
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>(
                "exhaust", fail, RetryStrategy.retrySame(), 3);
        LoopResult<String> r = loop.run("x");
        assertEquals(GiveUpReason.BUDGET_EXHAUSTED, r.terminal());
        assertEquals(3, r.attempts().size());
    }

    @Test
    void selfCorrectionLoopGivesUpWhenStrategyReturnsNull() {
        Verifier<String> fail = new Verifier<>() {
            @Override public String name() { return "fail"; }
            @Override public VerificationResult verify(String s) {
                return VerificationResult.fail(Verifier.Severity.BLOCK, "no");
            }
        };
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>(
                "giveup", fail, RetryStrategy.giveUpImmediately(), 5);
        LoopResult<String> r = loop.run("x");
        assertEquals(GiveUpReason.STRATEGY_RETURNED_NULL, r.terminal());
        assertEquals(1, r.attempts().size(), "null strategy short-circuits");
    }

    @Test
    void selfCorrectionLoopConvergesAfterRetries() {
        AtomicInteger calls = new AtomicInteger(0);
        Verifier<String> thirdTimeOk = new Verifier<>() {
            @Override public String name() { return "third"; }
            @Override public VerificationResult verify(String s) {
                int n = calls.incrementAndGet();
                return n >= 3
                        ? VerificationResult.pass("eventually ok")
                        : VerificationResult.fail(Verifier.Severity.BLOCK, "not yet");
            }
        };
        CorrectionStrategy<String> inc = (cur, fail, attempt) -> cur + "!";
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>(
                "converge", thirdTimeOk, inc, 5);
        LoopResult<String> r = loop.run("x");
        assertEquals(GiveUpReason.PASSED, r.terminal());
        assertEquals(3, r.attempts().size(), "passed on 3rd attempt");
        assertEquals(3, calls.get(), "verifier was called 3 times");
    }

    @Test
    void selfCorrectionLoopSurvivesVerifierCrash() {
        Verifier<String> crashing = new Verifier<>() {
            @Override public String name() { return "crash"; }
            @Override public VerificationResult verify(String s) {
                throw new IllegalStateException("boom");
            }
        };
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>(
                "crash", crashing, RetryStrategy.retrySame(), 2);
        LoopResult<String> r = loop.run("x");
        assertEquals(GiveUpReason.BUDGET_EXHAUSTED, r.terminal());
        assertEquals(2, r.attempts().size());
        assertFalse(r.attempts().get(0).result().passed());
    }

    @Test
    void selfCorrectionLoopSurvivesStrategyCrash() {
        Verifier<String> alwaysFail = new Verifier<>() {
            @Override public String name() { return "fail"; }
            @Override public VerificationResult verify(String s) {
                return VerificationResult.fail(Verifier.Severity.BLOCK, "nope");
            }
        };
        CorrectionStrategy<String> crashing = (cur, fail, attempt) -> {
            throw new RuntimeException("strategy boom");
        };
        SelfCorrectionLoop<String> loop = new SelfCorrectionLoop<>(
                "sc", alwaysFail, crashing, 4);
        LoopResult<String> r = loop.run("x");
        assertEquals(GiveUpReason.STRATEGY_RETURNED_NULL, r.terminal());
    }

    @Test
    void selfCorrectionLoopRejectsBadConstruction() {
        Verifier<String> pass = new Verifier<>() {
            @Override public String name() { return "p"; }
            @Override public VerificationResult verify(String s) { return VerificationResult.pass(); }
        };
        assertThrows(IllegalArgumentException.class,
                () -> new SelfCorrectionLoop<>(null, pass, RetryStrategy.retrySame(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new SelfCorrectionLoop<>("", pass, RetryStrategy.retrySame(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new SelfCorrectionLoop<>("x", null, RetryStrategy.retrySame(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new SelfCorrectionLoop<>("x", pass, null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new SelfCorrectionLoop<>("x", pass, RetryStrategy.retrySame(), 0));
    }

    @Test
    void heuristicVerifierNonEmptyCheck() {
        // HeuristicVerifier with a NonEmptyCheck fails on "" and
        // passes on "ok".
        HeuristicVerifier v = new HeuristicVerifier("non-empty",
                java.util.List.of(new HeuristicVerifier.NonEmptyCheck()));
        assertTrue(v.verify("ok").passed());
        assertFalse(v.verify("").passed());
        assertFalse(v.verify("   ").passed());
    }

    @Test
    void giveUpReasonTaxonomyIsStable() {
        // The runtime / audit log branches on these; if the
        // names change, the front-end breaks.
        assertEquals(4, GiveUpReason.values().length,
                "GiveUpReason taxonomy drifted — review audit log consumers");
        assertNotNull(GiveUpReason.valueOf("PASSED"));
        assertNotNull(GiveUpReason.valueOf("BUDGET_EXHAUSTED"));
        assertNotNull(GiveUpReason.valueOf("STRATEGY_RETURNED_NULL"));
        assertNotNull(GiveUpReason.valueOf("INTERRUPTED"));
    }
}
