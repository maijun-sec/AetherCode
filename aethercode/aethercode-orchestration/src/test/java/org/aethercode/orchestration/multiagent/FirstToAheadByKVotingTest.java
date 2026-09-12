package org.aethercode.orchestration.multiagent;

import org.aethercode.orchestration.multiagent.AgentFn;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FirstToAheadByKVotingTest {

    @Test
    void earlyStopWhenOneCandidateLeadsByK() {
        // 3 agents, all return "A". With k=1, one vote is enough.
        List<AgentFn<String>> agents = List.of(
            (prompt, peers) -> "A",
            (prompt, peers) -> "A",
            (prompt, peers) -> "B"
        );
        FirstToAheadByKVoting<String> v = new FirstToAheadByKVoting<>(1, 10, () -> "A");
        EnsembleResult<String> r = v.run("p", agents);
        assertEquals("A", r.winner());
        assertTrue((int) r.metadata().get("totalSamples") <= 2,
            "should stop early, got " + r.metadata().get("totalSamples"));
        assertEquals(true, r.metadata().get("earlyStop"));
    }

    @Test
    void needsMoreSamplesForK2() {
        List<AgentFn<String>> agents = List.of(
            (prompt, peers) -> "A",
            (prompt, peers) -> "A",
            (prompt, peers) -> "B"
        );
        FirstToAheadByKVoting<String> v = new FirstToAheadByKVoting<>(2, 10, () -> "A");
        EnsembleResult<String> r = v.run("p", agents);
        assertEquals("A", r.winner());
        // With 2 A, 0 B after 2 samples: margin = 2 - 0 = 2 >= k=2, so we break at sample 2
        int total = (int) r.metadata().get("totalSamples");
        assertTrue(total >= 2, "expected at least 2 samples, got " + total);
    }

    @Test
    void fallBackToMostVotedWhenNeverReachesK() {
        AtomicInteger n = new AtomicInteger();
        List<AgentFn<String>> agents = List.of(
            (prompt, peers) -> { n.incrementAndGet(); return "A"; },
            (prompt, peers) -> { n.incrementAndGet(); return "B"; }
        );
        FirstToAheadByKVoting<String> v = new FirstToAheadByKVoting<>(10, 4, () -> "A");
        // maxSamples = 2*4 = 8; with k=10 we never reach
        EnsembleResult<String> r = v.run("p", agents);
        assertNotNull(r.winner());
        // Fallback picks most-voted (A appears 5 times, B 3 times)
        assertEquals("A", r.winner());
    }

    @Test
    void recommendedKForTaskLength() {
        assertEquals(1, FirstToAheadByKVoting.recommendedKForTaskLength(1));
        assertEquals(1, FirstToAheadByKVoting.recommendedKForTaskLength(2));
        assertTrue(FirstToAheadByKVoting.recommendedKForTaskLength(100) > 1);
        assertTrue(FirstToAheadByKVoting.recommendedKForTaskLength(1_000_000) >= 10);
    }

    @Test
    void invalidKRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new FirstToAheadByKVoting<>(0, 10, () -> "x"));
    }

    @Test
    void invalidSamplesPerAgentRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> new FirstToAheadByKVoting<>(1, 0, () -> "x"));
    }

    @Test
    void emptyAgentsRejected() {
        FirstToAheadByKVoting<String> v = new FirstToAheadByKVoting<>(1, 5, () -> "x");
        assertThrows(IllegalArgumentException.class, () -> v.run("p", List.of()));
    }

    @Test
    void strategyName() {
        FirstToAheadByKVoting<String> v = new FirstToAheadByKVoting<>(1, 5, () -> "x");
        assertEquals("first-to-ahead-by-k", v.toString());
    }

    @Test
    void metadataContainsKey() {
        List<AgentFn<String>> agents = List.of((prompt, peers) -> "A");
        FirstToAheadByKVoting<String> v = new FirstToAheadByKVoting<>(1, 3, () -> "A");
        EnsembleResult<String> r = v.run("p", agents);
        assertEquals("first-to-ahead-by-k", r.metadata().get("strategy"));
        assertEquals(1, r.metadata().get("k"));
    }

    @Test
    void allSamplesInResult() {
        List<AgentFn<String>> agents = List.of(
            (prompt, peers) -> "A",
            (prompt, peers) -> "B"
        );
        FirstToAheadByKVoting<String> v = new FirstToAheadByKVoting<>(2, 4, () -> "C");
        EnsembleResult<String> r = v.run("p", agents);
        // Should produce up to 8 samples
        assertTrue(r.perAgent().size() > 0);
    }
}
