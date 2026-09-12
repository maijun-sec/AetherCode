package org.aethercode.orchestration.multiagent;

import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DebateStrategy}.
 */
class DebateStrategyTest {

    private static AgentFn<String> fixed(String name, String out) {
        return new AgentFn<>() {
            @Override public String name() { return name; }
            @Override public String respond(String prompt, List<String> peers) { return out; }
        };
    }

    /* ----------------------- constructor ----------------------- */

    @Test
    void constructorRejectsZeroRounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new DebateStrategy<String>(0));
    }

    /* ----------------------- single round = vote ----------------------- */

    @Test
    void singleRoundBehavesLikePlurality() {
        DebateStrategy<String> s = new DebateStrategy<>(1);
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "yes"), fixed("b", "yes"), fixed("c", "no")));
        assertEquals("yes", r.winner());
        assertEquals(1, r.metadata().get("rounds"));
        // 2 of 3 is not consensus; only unanimous is.
        assertEquals(false, r.metadata().get("consensus"));
    }

    /* ----------------------- multi-round convergence ----------------------- */

    @Test
    void convergesToConsensusAfterDebate() {
        // Round 1: a=yes, b=no, c=yes.
        // After seeing others' outputs, b switches to "yes" in round 2.
        AtomicInteger round = new AtomicInteger();
        AgentFn<String> b = new AgentFn<>() {
            @Override public String name() { return "b"; }
            @Override public String respond(String prompt, List<String> peers) {
                int r = round.incrementAndGet();
                if (r == 1) return "no";  // first call
                return "yes";  // after seeing others
            }
        };
        DebateStrategy<String> s = new DebateStrategy<>(5);
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "yes"), b, fixed("c", "yes")));
        assertEquals("yes", r.winner());
        assertEquals(2, r.metadata().get("rounds"));
        assertEquals(true, r.metadata().get("consensus"));
        assertEquals(true, r.metadata().get("early_stopped"));
    }

    @Test
    void runsAllRoundsWhenNoConvergence() {
        // Each agent sticks to its position. No consensus.
        DebateStrategy<String> s = new DebateStrategy<>(3);
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "yes"), fixed("b", "no"), fixed("c", "maybe")));
        assertEquals(3, r.metadata().get("rounds"));
        assertEquals(false, r.metadata().get("consensus"));
        // first registered ("a" with "yes") wins by tie-break
        assertEquals("yes", r.winner());
    }

    @Test
    void earlyStoppedOnlyWhenConsensusBeforeMax() {
        DebateStrategy<String> s = new DebateStrategy<>(10);
        // 3 agents, all agree from the start → 1 round, early stop
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "x"), fixed("b", "x"), fixed("c", "x")));
        assertEquals(1, r.metadata().get("rounds"));
        assertEquals(true, r.metadata().get("early_stopped"));
    }

    /* ----------------------- peer visibility ----------------------- */

    @Test
    void agentsSeeOtherAgentsOutputsInRound2() {
        AtomicReference<List<String>> round1Peers = new AtomicReference<>();
        AtomicReference<List<String>> round2Peers = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        // Spy returns a different value in round 1 vs round 2 to force
        // a non-consensus second round (otherwise debate stops after 1).
        AgentFn<String> spy = new AgentFn<>() {
            @Override public String name() { return "spy"; }
            @Override public String respond(String prompt, List<String> peers) {
                int n = calls.incrementAndGet();
                if (n == 1) { round1Peers.set(peers); return "y"; }
                else        { round2Peers.set(peers); return "y"; }
            }
        };
        DebateStrategy<String> s = new DebateStrategy<>(2);
        // Use agents with mixed outputs so consensus isn't reached in round 1.
        s.run("q", List.of(fixed("a", "x"), spy, fixed("c", "z")));
        // Round 1: empty peers.
        assertEquals(List.of(), round1Peers.get());
        // Round 2: peers = the OTHER agents' round-1 outputs.
        assertEquals(List.of("x", "z"), round2Peers.get());
    }

    @Test
    void peersExcludeTheAgentItself() {
        // Agent "a" should see only b and c's outputs, not its own.
        AtomicReference<List<String>> aPeers = new AtomicReference<>();
        AgentFn<String> a = new AgentFn<>() {
            @Override public String name() { return "a"; }
            @Override public String respond(String prompt, List<String> peers) {
                aPeers.set(peers);
                return "a-out";
            }
        };
        DebateStrategy<String> s = new DebateStrategy<>(2);
        s.run("q", List.of(a, fixed("b", "b-out"), fixed("c", "c-out")));
        // a sees b-out and c-out, not a-out.
        assertEquals(List.of("b-out", "c-out"), aPeers.get());
    }

    /* ----------------------- error containment ----------------------- */

    @Test
    void agentCrashIsContainedInDebate() {
        AgentFn<String> broken = new AgentFn<>() {
            @Override public String name() { return "broken"; }
            @Override public String respond(String prompt, List<String> peers) {
                throw new RuntimeException("boom");
            }
        };
        DebateStrategy<String> s = new DebateStrategy<>(2);
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "yes"), broken, fixed("c", "yes")));
        // "yes" wins (2 of 3 ignore the broken sentinel).
        assertEquals("yes", r.winner());
    }

    /* ----------------------- accessors ----------------------- */

    @Test
    void maxRoundsAccessor() {
        DebateStrategy<String> s = new DebateStrategy<>(5);
        assertEquals(5, s.maxRounds());
    }

    @Test
    void defaultMaxRoundsIsThree() {
        DebateStrategy<String> s = new DebateStrategy<>();
        assertEquals(3, s.maxRounds());
    }
}
