package org.aethercode.evals.sdk.cost;

import org.aethercode.orchestration.perf.ActionCache;
import org.aethercode.orchestration.perf.CostCeiling;
import org.aethercode.orchestration.perf.TokenCounter;
import org.aethercode.permission.CommandAllowlist;
import org.aethercode.permission.CommandAllowlist.Decision;
import org.aethercode.permission.CommandAllowlist.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-sdk-4: AetherCode Cost / Safety Interface conformance.
 *
 * <p>Companion to {@code CostSafetyRobustnessTest} (R-eval-8). The capability
 * suite proves the cost / safety design (3-axis budget, permission
 * verdict, grant, URL/Shell guard) on a self-contained model. This
 * suite proves the actual classes the orchestration runtime and the
 * front-end reach into:</p>
 *
 * <ul>
 *   <li>{@code orchestration.perf.CostCeiling} — 3-axis budget, `>=`
 *       semantics, budget-exhausted outcome.</li>
 *   <li>{@code orchestration.perf.ActionCache} — LRU on access order.</li>
 *   <li>{@code orchestration.perf.TokenCounter} — functional interface
 *       with a sensible zero default.</li>
 *   <li>{@code permission.CommandAllowlist} — bash / shell guard with
 *       3 match kinds (EXACT, PREFIX, REGEX), deny-wins precedence,
 *       and configurable default verdict.</li>
 * </ul>
 */
class SdkCostSafetyInterfaceTest {

    /* ---------------- CostCeiling ---------------- */

    @Test
    void costCeilingRejectsNonPositiveBudget() {
        assertThrows(IllegalArgumentException.class,
                () -> new CostCeiling(0, 1_000L, 1_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new CostCeiling(1, 0L, 1_000L));
        assertThrows(IllegalArgumentException.class,
                () -> new CostCeiling(1, 1_000L, 0L));
    }

    @Test
    void costCeilingStartsWithinBudget() {
        CostCeiling c = new CostCeiling(10, 60_000L, 100_000L);
        assertFalse(c.exceeded(), "fresh ceiling should not be exceeded");
        assertEquals(0, c.calls());
        assertEquals(0L, c.tokens());
        assertEquals(10, c.maxCalls());
        assertEquals(60_000L, c.maxMillis());
        assertEquals(100_000L, c.maxTokens());
    }

    @Test
    void costCeilingTripsOnCallLimit() {
        CostCeiling c = new CostCeiling(2, 60_000L, 100_000L);
        c.recordCall(0L);
        assertFalse(c.exceeded());
        // 2nd call already meets `>=` threshold, recordCall returns true.
        assertTrue(c.recordCall(0L), "exceeded call budget");
    }

    @Test
    void costCeilingTripsOnTokenLimit() {
        CostCeiling c = new CostCeiling(100, 60_000L, 50L);
        c.recordCall(40L);
        assertFalse(c.exceeded());
        // 40 + 10 = 50 -> >= maxTokens -> exceeded
        assertTrue(c.recordCall(10L), "exceeded token budget");
    }

    @Test
    void costCeilingRecordsAllThreeAxes() {
        CostCeiling c = new CostCeiling(10, 60_000L, 1_000L);
        c.recordCall(100L);
        assertEquals(1, c.calls());
        assertEquals(100L, c.tokens());
        CostCeiling.Usage u = c.usage();
        assertEquals(1, u.calls());
        assertEquals(100L, u.tokens());
        assertEquals(10, u.maxCalls());
        assertEquals(60_000L, u.maxMillis());
        assertEquals(1_000L, u.maxTokens());
    }

    @Test
    void costCeilingBuilderProducesInstance() {
        CostCeiling c = CostCeiling.builder()
                .maxCalls(5)
                .maxMillis(10_000L)
                .maxTokens(500L)
                .build();
        assertEquals(5, c.maxCalls());
        assertEquals(10_000L, c.maxMillis());
        assertEquals(500L, c.maxTokens());
    }

    /* ---------------- ActionCache (LRU) ---------------- */

    @Test
    void actionCacheReturnsCachedValueForSameKey() {
        ActionCache cache = new ActionCache(4);
        org.aethercode.orchestration.verifier.Verifier.VerificationResult v1 =
                org.aethercode.orchestration.verifier.Verifier.VerificationResult.pass("ok");
        cache.put("k1", v1);
        assertEquals(v1, cache.get("k1"));
        assertEquals(1, cache.size());
        assertEquals(1L, cache.hits());
    }

    @Test
    void actionCacheMissIncrementsMisses() {
        ActionCache cache = new ActionCache(4);
        org.aethercode.orchestration.verifier.Verifier.VerificationResult v1 =
                org.aethercode.orchestration.verifier.Verifier.VerificationResult.pass("ok");
        cache.put("a", v1);
        cache.get("a"); // hit
        cache.get("nope"); // miss
        assertEquals(1L, cache.hits());
        assertEquals(1L, cache.misses());
        assertEquals(0.5, cache.hitRate(), 0.001);
    }

    @Test
    void actionCacheGetOrComputeCachesLoaderResult() {
        ActionCache cache = new ActionCache(4);
        org.aethercode.orchestration.verifier.Verifier.VerificationResult v =
                org.aethercode.orchestration.verifier.Verifier.VerificationResult.pass("ok");
        org.aethercode.orchestration.verifier.Verifier.VerificationResult first =
                cache.getOrCompute("k", k -> v);
        org.aethercode.orchestration.verifier.Verifier.VerificationResult second =
                cache.getOrCompute("k", k -> {
                    throw new AssertionError("loader should not be called on hit");
                });
        assertEquals(v, first);
        assertEquals(v, second);
        assertEquals(1, cache.size());
    }

    /* ---------------- TokenCounter ---------------- */

    @Test
    void tokenCounterZeroReturnsZero() {
        assertEquals(0L, TokenCounter.<String>zero().count("anything"));
    }

    @Test
    void tokenCounterCharQuotientHeuristic() {
        // charQuotient is /4 (4 chars per token).
        assertEquals(0L, TokenCounter.<String>charQuotient().count(""));
        assertEquals(1L, TokenCounter.<String>charQuotient().count("abcd"));
        assertEquals(2L, TokenCounter.<String>charQuotient().count("abcdefgh"));
    }

    @Test
    void tokenCounterOfAdaptsToLongFunction() {
        TokenCounter<String> tc = TokenCounter.of(String::length);
        assertEquals(5L, tc.count("hello"));
    }

    @Test
    void tokenCounterOrElseFallsBackOnZero() {
        TokenCounter<String> primary = TokenCounter.zero();
        TokenCounter<String> fallback = TokenCounter.of(String::length);
        TokenCounter<String> chain = TokenCounter.orElse(primary, fallback);
        // primary returns 0 -> fallback kicks in.
        assertEquals(5L, chain.count("hello"));
    }

    /* ---------------- CommandAllowlist ---------------- */

    @Test
    void commandAllowlistEmptyCommandIsDenied() {
        CommandAllowlist al = new CommandAllowlist();
        assertEquals(Verdict.DENY, al.check("").verdict());
        assertEquals(Verdict.DENY, al.check(null).verdict());
    }

    @Test
    void commandAllowlistExactAllow() {
        CommandAllowlist al = new CommandAllowlist()
                .allowExact("ls")
                .defaultVerdict(Verdict.DENY);
        assertEquals(Verdict.ALLOW, al.check("ls").verdict());
        assertEquals(Verdict.DENY, al.check("rm -rf /").verdict());
    }

    @Test
    void commandAllowlistPrefixAllow() {
        CommandAllowlist al = new CommandAllowlist()
                .allowPrefix("git status")
                .defaultVerdict(Verdict.DENY);
        assertEquals(Verdict.ALLOW, al.check("git status").verdict());
        assertEquals(Verdict.ALLOW, al.check("git status -s").verdict());
        assertEquals(Verdict.DENY, al.check("git commit -m x").verdict());
    }

    @Test
    void commandAllowlistRegexAllow() {
        CommandAllowlist al = new CommandAllowlist()
                .allowRegex("^echo\\s")
                .defaultVerdict(Verdict.DENY);
        assertEquals(Verdict.ALLOW, al.check("echo hello").verdict());
        assertEquals(Verdict.ALLOW, al.check("echo world").verdict());
        assertEquals(Verdict.DENY, al.check("evil echo").verdict());
    }

    @Test
    void commandAllowlistDenyWinsOverAllow() {
        // A prefix allow for "git" plus a deny for "git push" should
        // still block "git push" because deny always wins.
        CommandAllowlist al = new CommandAllowlist()
                .allowPrefix("git ")
                .denyExact("git push")
                .defaultVerdict(Verdict.DENY);
        assertEquals(Verdict.DENY, al.check("git push").verdict());
        assertEquals(Verdict.ALLOW, al.check("git status").verdict());
    }

    @Test
    void commandAllowlistDefaultVerdictIsHonoured() {
        // Empty ruleset -> default applies.
        CommandAllowlist al = new CommandAllowlist().defaultVerdict(Verdict.ALLOW);
        assertEquals(Verdict.ALLOW, al.check("anything goes").verdict());
    }

    @Test
    void commandAllowlistSafeDefaultsIsConservative() {
        // The factory ships with a list of read-only commands allowed
        // and a few clearly destructive commands denied.
        CommandAllowlist al = CommandAllowlist.safeDefaults();
        assertEquals(Verdict.ALLOW, al.check("ls").verdict());
        assertEquals(Verdict.ALLOW, al.check("pwd").verdict());
        assertEquals(Verdict.ALLOW, al.check("git status").verdict());
        assertEquals(Verdict.DENY, al.check("rm -rf /").verdict());
        assertEquals(Verdict.DENY, al.check("shutdown").verdict());
    }

    @Test
    void commandAllowlistRulesListIsUnmodifiable() {
        CommandAllowlist al = new CommandAllowlist().allowExact("ls");
        List<CommandAllowlist.Rule> rules = al.rules();
        assertEquals(1, rules.size());
        // The returned list is a defensive copy; mutation must not affect the allowlist.
        assertThrows(UnsupportedOperationException.class, () -> rules.add(CommandAllowlist.Rule.exact("evil")));
        // And the original rule is still there.
        assertEquals(1, al.size());
    }

    @Test
    void commandAllowlistDecisionRecordHelpers() {
        Decision allow = Decision.allow("for testing");
        assertEquals(Verdict.ALLOW, allow.verdict());
        assertTrue(allow.allowed());
        Decision deny = Decision.deny("nope");
        assertEquals(Verdict.DENY, deny.verdict());
        assertFalse(deny.allowed());
        assertNotNull(allow.reason());
    }
}
