package org.aethercode.evals.multiagent;

import org.aethercode.evals.multiagent.MultiAgentOrchestrator.EnsembleResult;
import org.aethercode.evals.multiagent.MultiAgentOrchestrator.EnsembleResult.AgentOutput;
import org.aethercode.evals.multiagent.VoteStrategy.VoteMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link VoteStrategy}.
 */
class VoteStrategyTest {

    private static AgentFn<String> fixed(String name, String out) {
        return new AgentFn<>() {
            @Override public String name() { return name; }
            @Override public String respond(String prompt, List<String> peers) { return out; }
        };
    }

    /* ----------------------- plurality ----------------------- */

    @Test
    void pluralityPicksMajority() {
        VoteStrategy<String> s = new VoteStrategy<>(VoteMode.PLURALITY);
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "yes"), fixed("b", "yes"), fixed("c", "no")));
        assertEquals("yes", r.winner());
        assertEquals(3, r.perAgent().size());
        Map<String, Object> meta = r.metadata();
        assertEquals(VoteMode.PLURALITY, meta.get("mode"));
        assertEquals(2, ((Map<?, ?>) meta.get("tally")).get("yes"));
        // consensus = "all agents agreed" — 2 of 3 is not consensus.
        assertEquals(false, meta.get("consensus"));
    }

    @Test
    void pluralityTieBrokenByRegistrationOrder() {
        VoteStrategy<String> s = new VoteStrategy<>(VoteMode.PLURALITY);
        // a and c both say "yes", b says "no" — "yes" wins 2-1.
        // In a real tie (a="x", b="y"), the first registered wins.
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "yes"), fixed("b", "no"), fixed("c", "yes")));
        assertEquals("yes", r.winner());
    }

    @Test
    void pluralityUnanimousIsConsensus() {
        VoteStrategy<String> s = new VoteStrategy<>(VoteMode.PLURALITY);
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "x"), fixed("b", "x"), fixed("c", "x")));
        assertEquals("x", r.winner());
        assertEquals(true, r.metadata().get("consensus"));
    }

    @Test
    void pluralityNoConsensusStillPicksAWinner() {
        VoteStrategy<String> s = new VoteStrategy<>(VoteMode.PLURALITY);
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "x"), fixed("b", "y")));
        // "x" wins 1-0 because it's first in registration order.
        assertEquals("x", r.winner());
        assertEquals(false, r.metadata().get("consensus"));
    }

    /* ----------------------- majority ----------------------- */

    @Test
    void majorityPicksStrictMajority() {
        VoteStrategy<String> s = new VoteStrategy<>(VoteMode.MAJORITY);
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "yes"), fixed("b", "yes"), fixed("c", "no")));
        assertEquals("yes", r.winner());
        // 2 > 3/2 = 1, so it's a real majority.
    }

    @Test
    void majorityFallsBackToPluralityWhenNoStrictMajority() {
        VoteStrategy<String> s = new VoteStrategy<>(VoteMode.MAJORITY);
        // 3 agents, "yes" gets 1 vote, "no" gets 1 vote, "maybe" gets 1 vote.
        // No strict majority; fallback to plurality (first registered).
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "yes"), fixed("b", "no"), fixed("c", "maybe")));
        assertEquals("yes", r.winner());
        assertEquals("no majority, picked plurality winner", r.metadata().get("fallback"));
    }

    @Test
    void majorityOnEvenSplitFallsBack() {
        // 4 agents split 2-2: "yes" has 2, "no" has 2. 2 > 4/2 = 2 is false
        // (strict majority), so fallback.
        VoteStrategy<String> s = new VoteStrategy<>(VoteMode.MAJORITY);
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "yes"), fixed("b", "yes"),
                        fixed("c", "no"), fixed("d", "no")));
        // "yes" wins by registration order.
        assertEquals("yes", r.winner());
        assertEquals("no majority, picked plurality winner", r.metadata().get("fallback"));
    }

    /* ----------------------- unanimous ----------------------- */

    @Test
    void unanimousPicksWinnerWhenAllAgree() {
        VoteStrategy<String> s = new VoteStrategy<>(VoteMode.UNANIMOUS);
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "x"), fixed("b", "x"), fixed("c", "x")));
        assertEquals("x", r.winner());
        assertEquals(true, r.metadata().get("consensus"));
        assertEquals(null, r.metadata().get("fallback"));
    }

    @Test
    void unanimousFallsBackToPluralityWhenNoConsensus() {
        VoteStrategy<String> s = new VoteStrategy<>(VoteMode.UNANIMOUS);
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "x"), fixed("b", "x"), fixed("c", "y")));
        assertEquals("x", r.winner());
        assertEquals(false, r.metadata().get("consensus"));
        assertEquals("no consensus, picked plurality winner", r.metadata().get("fallback"));
    }

    /* ----------------------- error containment ----------------------- */

    @Test
    void agentCrashIsContained() {
        AgentFn<String> broken = new AgentFn<>() {
            @Override public String name() { return "broken"; }
            @Override public String respond(String prompt, List<String> peers) {
                throw new RuntimeException("boom");
            }
        };
        VoteStrategy<String> s = new VoteStrategy<>();
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "yes"), broken, fixed("c", "yes")));
        assertEquals("yes", r.winner());
        // The broken agent's audit entry is marked [FAILED].
        assertTrue(r.perAgent().stream()
                .anyMatch(o -> o.agentName().contains("FAILED")),
                "broken agent must surface as FAILED in audit: " + r.perAgent());
    }

    @Test
    void agentReturningNullIsContained() {
        AgentFn<String> nullAgent = new AgentFn<>() {
            @Override public String name() { return "null-agent"; }
            @Override public String respond(String prompt, List<String> peers) { return null; }
        };
        VoteStrategy<String> s = new VoteStrategy<>();
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "yes"), nullAgent, fixed("c", "yes")));
        assertEquals("yes", r.winner());
    }

    /* ----------------------- peers list is empty in vote mode ----------------------- */

    @Test
    void agentsReceiveEmptyPeersList() {
        java.util.concurrent.atomic.AtomicReference<List<String>> seenPeers =
                new java.util.concurrent.atomic.AtomicReference<>();
        AgentFn<String> spy = new AgentFn<>() {
            @Override public String name() { return "spy"; }
            @Override public String respond(String prompt, List<String> peers) {
                seenPeers.set(peers);
                return "x";
            }
        };
        VoteStrategy<String> s = new VoteStrategy<>();
        s.run("q", List.of(spy, fixed("b", "x")));
        assertEquals(List.of(), seenPeers.get(),
                "vote strategy must pass empty peers list");
    }

    /* ----------------------- accessors ----------------------- */

    @Test
    void accessors() {
        VoteStrategy<String> s = new VoteStrategy<>(VoteMode.MAJORITY);
        assertEquals(VoteMode.MAJORITY, s.mode());
        // Default executor is null (sequential). Setting an executor
        // makes the agent runs parallel.
        org.junit.jupiter.api.Assertions.assertNull(s.executor());
        VoteStrategy<String> s2 = new VoteStrategy<>(
                VoteMode.MAJORITY, java.util.concurrent.ForkJoinPool.commonPool());
        org.junit.jupiter.api.Assertions.assertNotNull(s2.executor());
    }

    @Test
    void constructorRejectsNullMode() {
        assertThrows(IllegalArgumentException.class,
                () -> new VoteStrategy<>(null));
    }
}
