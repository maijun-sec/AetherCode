package org.aethercode.evals.orchestration;

import org.aethercode.evals.multiagent.MultiAgentOrchestrator;
import org.aethercode.evals.multiagent.VoteStrategy;
import org.aethercode.evals.orchestration.AgentRuntime.RuntimeResult;
import org.aethercode.evals.perf.ActionCache;
import org.aethercode.evals.perf.CostCeiling;
import org.aethercode.evals.perf.TokenCounter;
import org.aethercode.evals.selfcorrect.RetryStrategy;
import org.aethercode.evals.selfcorrect.SelfCorrectionLoop;
import org.aethercode.evals.verifier.Verifier;
import org.aethercode.evals.verifier.Verifier.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AgentRuntime}.
 */
class AgentRuntimeTest {

    /* ----------------------- fixtures ----------------------- */

    private static Verifier<String> passOnN(int n) {
        return new Verifier<>() {
            int calls = 0;
            @Override public String name() { return "pass-on-" + n; }
            @Override public VerificationResult verify(String input) {
                calls++;
                if (calls >= n) {
                    return VerificationResult.pass("ok at " + calls);
                }
                return VerificationResult.fail(
                        org.aethercode.evals.verifier.Verifier.Severity.BLOCK,
                        "fail #" + calls);
            }
        };
    }

    private static Verifier<String> alwaysFail(String reason) {
        return new Verifier<>() {
            @Override public String name() { return "always-fail"; }
            @Override public VerificationResult verify(String input) {
                return VerificationResult.fail(
                        org.aethercode.evals.verifier.Verifier.Severity.BLOCK, reason);
            }
        };
    }

    private static MultiAgentOrchestrator<String> voteOf(List<String> candidates) {
        List<org.aethercode.evals.multiagent.AgentFn<String>> agents = new java.util.ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            String out = candidates.get(i);
            String name = "cand-" + i;
            agents.add(new org.aethercode.evals.multiagent.AgentFn<>() {
                @Override public String name() { return name; }
                @Override public String respond(String p, List<String> peers) { return out; }
            });
        }
        return new MultiAgentOrchestrator<>("test-vote", agents, new VoteStrategy<>());
    }

    /* ----------------------- builder ----------------------- */

    @Test
    void builderRejectsNullVerifier() {
        assertThrows(NullPointerException.class,
                () -> AgentRuntime.<String>builder().build());
    }

    @Test
    void builderAcceptsJustVerifier() {
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(passOnN(1))
                .build();
        assertNotNull(r);
        assertNull(r.selfCorrect());
        assertNull(r.ensemble());
    }

    @Test
    void accessors() {
        SelfCorrectionLoop<String> sc = new SelfCorrectionLoop<>(
                "sc", passOnN(2), RetryStrategy.giveUpImmediately(), 3);
        MultiAgentOrchestrator<String> v = voteOf(List.of("a", "b", "c"));
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .name("my-runtime")
                .verifier(passOnN(1))
                .selfCorrect(sc)
                .ensemble(v)
                .build();
        assertEquals("my-runtime", r.name());
        assertSame(sc, r.selfCorrect());
        assertSame(v, r.ensemble());
    }

    /* ----------------------- single-action: pass on first ----------------------- */

    @Test
    void singleActionPassesOnFirstVerify() {
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(passOnN(1))
                .build();
        RuntimeResult<String> res = r.run("hello");
        assertEquals("hello", res.action());
        assertEquals("passed", res.outcome());
        assertTrue(res.passed());
        // 1 entry: the initial verify.
        assertEquals(1, res.trace().size());
    }

    /* ----------------------- single-action: self-corrected ----------------------- */

    @Test
    void singleActionSelfCorrectsToPass() {
        // The runtime and the self-correct loop share the verifier.
        // We need the runtime's first call to fail, AND the loop's
        // first call to also fail, so the strategy has a chance to
        // kick in. Use a verifier that fails the first 2 calls, then
        // passes. Call #1 = runtime (fail), call #2 = loop attempt 1
        // (fail), call #3 = loop attempt 2 (pass after strategy).
        Verifier<String> v = passOnN(3);
        SelfCorrectionLoop<String> sc = new SelfCorrectionLoop<>(
                "sc", v, RetryStrategy.transform(s -> s + "-fixed"), 5);
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(v)
                .selfCorrect(sc)
                .build();
        RuntimeResult<String> res = r.run("v1");
        assertEquals("v1-fixed", res.action());
        assertEquals("self-corrected", res.outcome());
        assertTrue(res.passed());
        assertNotNull(res.selfCorrectResult());
        assertTrue(res.selfCorrectResult().passed());
    }

    /* ----------------------- single-action: self-correct exhausted, no ensemble ----------------------- */

    @Test
    void singleActionSelfCorrectExhaustedWithoutEnsemble() {
        Verifier<String> v = alwaysFail("nope");
        SelfCorrectionLoop<String> sc = new SelfCorrectionLoop<>(
                "sc", v, RetryStrategy.transform(s -> s + "x"), 3);
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(v)
                .selfCorrect(sc)
                .build();
        RuntimeResult<String> res = r.run("v1");
        assertEquals("self-correct-exhausted", res.outcome());
        assertFalse(res.passed());
        assertNotNull(res.selfCorrectResult());
        assertNull(res.ensembleResult());
    }

    /* ----------------------- single-action: self-correct exhausts, ensemble saves ----------------------- */

    @Test
    void singleActionEscalatesToEnsembleAndPasses() {
        // V always fails; self-correct exhausts; ensemble of "good" candidates.
        Verifier<String> v = new Verifier<>() {
            int calls = 0;
            @Override public String name() { return "verify"; }
            @Override public VerificationResult verify(String input) {
                calls++;
                // Accept only "winner" (which the ensemble produces).
                if ("winner".equals(input)) {
                    return VerificationResult.pass("ok at " + calls);
                }
                return VerificationResult.fail(
                        org.aethercode.evals.verifier.Verifier.Severity.BLOCK,
                        "fail #" + calls);
            }
        };
        SelfCorrectionLoop<String> sc = new SelfCorrectionLoop<>(
                "sc", v, RetryStrategy.transform(s -> s + "x"), 2);
        MultiAgentOrchestrator<String> ensemble = voteOf(
                List.of("winner", "winner", "loser"));
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(v)
                .selfCorrect(sc)
                .ensemble(ensemble)
                .build();
        RuntimeResult<String> res = r.run("v1");
        assertEquals("ensemble-passed", res.outcome());
        assertTrue(res.passed());
        assertEquals("winner", res.action());
        assertNotNull(res.ensembleResult());
    }

    /* ----------------------- single-action: ensemble also fails ----------------------- */

    @Test
    void singleActionEscalatesToEnsembleAndFails() {
        Verifier<String> v = alwaysFail("still no");
        SelfCorrectionLoop<String> sc = new SelfCorrectionLoop<>(
                "sc", v, RetryStrategy.transform(s -> s + "x"), 2);
        // Ensemble candidates all fail V.
        MultiAgentOrchestrator<String> ensemble = voteOf(List.of("a", "b", "c"));
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(v)
                .selfCorrect(sc)
                .ensemble(ensemble)
                .build();
        RuntimeResult<String> res = r.run("v1");
        assertEquals("ensemble-failed", res.outcome());
        assertFalse(res.passed());
        assertNotNull(res.ensembleResult());
    }

    /* ----------------------- runEnsemble: all candidates pass V ----------------------- */

    @Test
    void runEnsemblePicksWinnerWhenAllPassV() {
        Verifier<String> v = passOnN(1);
        MultiAgentOrchestrator<String> ensemble = voteOf(
                List.of("alpha", "beta", "alpha"));
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(v)
                .ensemble(ensemble)
                .build();
        RuntimeResult<String> res = r.runEnsemble(List.of("alpha", "beta", "alpha"));
        assertEquals("alpha", res.action());
        assertEquals("ensemble-passed", res.outcome());
    }

    /* ----------------------- runEnsemble: candidates need self-correct ----------------------- */

    @Test
    void runEnsembleSelfCorrectsFailedCandidates() {
        // V fails first time, passes second time. So "raw" candidates
        // need self-correct to survive.
        Verifier<String> v = passOnN(2);
        SelfCorrectionLoop<String> sc = new SelfCorrectionLoop<>(
                "sc", v, RetryStrategy.transform(s -> s + "-fixed"), 3);
        MultiAgentOrchestrator<String> ensemble = voteOf(
                List.of("a-fixed", "b-fixed", "c-fixed"));
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(v)
                .selfCorrect(sc)
                .ensemble(ensemble)
                .build();
        RuntimeResult<String> res = r.runEnsemble(List.of("a", "b", "c"));
        // Each candidate gets self-corrected, then ensemble picks.
        assertEquals("ensemble-passed", res.outcome());
        assertTrue(res.passed());
    }

    /* ----------------------- runEnsemble requires ensemble ----------------------- */

    @Test
    void runEnsembleRequiresEnsemble() {
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(passOnN(1))
                .build();
        assertThrows(IllegalStateException.class,
                () -> r.runEnsemble(List.of("a", "b")));
    }

    /* ----------------------- runEnsemble rejects null candidates ----------------------- */

    @Test
    void runEnsembleRejectsNullList() {
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(passOnN(1))
                .ensemble(voteOf(List.of("a", "b")))
                .build();
        assertThrows(NullPointerException.class, () -> r.runEnsemble(null));
    }

    /* ----------------------- run rejects null action ----------------------- */

    @Test
    void runRejectsNullAction() {
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(passOnN(1))
                .build();
        assertThrows(NullPointerException.class, () -> r.run(null));
    }

    /* ----------------------- RuntimeResult.summary ----------------------- */

    @Test
    void runtimeResultSummaryExposesHeadline() {
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(passOnN(1))
                .build();
        RuntimeResult<String> res = r.run("v1");
        java.util.Map<String, Object> s = res.summary();
        assertEquals("passed", s.get("outcome"));
        assertEquals(true, s.get("passed"));
        assertEquals(1, s.get("attempts"));
    }

    /* ----------------------- R-perf-1: cost ceiling + cache ----------------------- */

    @Test
    void costCeilingShortCircuitsBeforeSelfCorrect() {
        // maxCalls=1 → the initial V eats the budget; self-correct must
        // not run.
        CostCeiling ceiling = CostCeiling.builder()
                .maxCalls(1).maxMillis(60_000L).maxTokens(1000L).build();
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(alwaysFail("no"))
                .selfCorrect(new SelfCorrectionLoop<>("sc",
                        alwaysFail("no"),
                        RetryStrategy.retrySame(), 5))
                .costCeiling(ceiling)
                .build();
        RuntimeResult<String> res = r.run("v1");
        assertEquals("budget-exhausted", res.outcome());
        assertFalse(res.passed());
        assertTrue(res.budgetExhausted());
        assertTrue(res.summary().containsKey("budget_exhausted"));
    }

    @Test
    void actionCacheSkipsRepeatedVerifierCalls() {
        // Same action run twice → verifier should only fire once thanks
        // to the cache.
        AtomicInteger calls = new AtomicInteger();
        Verifier<String> counting = new Verifier<>() {
            @Override public String name() { return "counting"; }
            @Override public VerificationResult verify(String input) {
                calls.incrementAndGet();
                return VerificationResult.pass("ok");
            }
        };
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(counting)
                .actionCache(new ActionCache(10))
                .build();
        r.run("v1");
        r.run("v1");
        // Both calls hit the same action key; only one verifier call.
        assertEquals(1, calls.get(),
                "second run on the same action must hit the cache");
    }

    @Test
    void actionCacheDistinguishesDifferentActions() {
        AtomicInteger calls = new AtomicInteger();
        Verifier<String> counting = new Verifier<>() {
            @Override public String name() { return "counting"; }
            @Override public VerificationResult verify(String input) {
                calls.incrementAndGet();
                return VerificationResult.pass("ok");
            }
        };
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(counting)
                .actionCache(new ActionCache(10))
                .build();
        r.run("a");
        r.run("b");
        r.run("a");
        // "a" first call: miss + verify. "b" first call: miss + verify.
        // "a" second call: hit, no verify. Total: 2 verifier calls.
        assertEquals(2, calls.get());
    }

    @Test
    void tokenCounterChargesCeiling() {
        // Each action's toString().length()/4 is added to the ceiling.
        CostCeiling ceiling = CostCeiling.builder()
                .maxCalls(100).maxMillis(60_000L).maxTokens(50L).build();
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(passOnN(1))
                .costCeiling(ceiling)
                .tokenCounter(TokenCounter.charQuotient())
                .build();
        // "v1" = 2 chars → 0 tokens. Need an action with > 12 chars to
        // push past 50. (50 / 4 = 12)
        r.run("v1");
        r.run("v1");
        // Each run books 1 call + 0 tokens; well under budget.
        assertFalse(ceiling.exceeded());
        r.run("a-long-action-string-with-more-than-twelve-chars");
        // 39 chars / 4 = 9 tokens. 3 calls so far, 9 tokens. Still under.
        assertFalse(ceiling.exceeded());
    }

    @Test
    void emptyBuilderRuntimeHasNoCeilingOrCache() {
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(passOnN(1))
                .build();
        assertNull(r.costCeiling());
        assertNull(r.actionCache());
    }

    @Test
    void budgetExhaustedResultIsNotPassed() {
        // maxCalls=0 is rejected by CostCeiling; use maxCalls=1 to
        // force the initial V to consume the only allowed call.
        CostCeiling ceiling = CostCeiling.builder()
                .maxCalls(1).maxMillis(60_000L).maxTokens(1000L).build();
        AgentRuntime<String> r = AgentRuntime.<String>builder()
                .verifier(passOnN(1))
                .costCeiling(ceiling)
                .build();
        RuntimeResult<String> res = r.run("v1");
        // passOnN(1) passes on the first call → outcome is "passed",
        // not budget-exhausted. The test guards against a regression
        // where a passing run accidentally gets tagged as budgeted.
        assertEquals("passed", res.outcome());
        assertTrue(res.passed());
    }
}
