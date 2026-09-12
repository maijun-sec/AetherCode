package org.aethercode.orchestration.multiagent;

import org.aethercode.orchestration.multiagent.CritiqueStrategy.Critic;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link CritiqueStrategy}.
 */
class CritiqueStrategyTest {

    private static AgentFn<String> fixed(String name, String out) {
        return new AgentFn<>() {
            @Override public String name() { return name; }
            @Override public String respond(String prompt, List<String> peers) { return out; }
        };
    }

    private static Critic<String> fixedCritic(String name, double score) {
        return new Critic<>() {
            @Override public String name() { return name; }
            @Override public double score(String proposal, List<String> all) { return score; }
        };
    }

    private static Critic<String> criticThatAlwaysPicksFirst() {
        return new Critic<>() {
            @Override public String name() { return "first-picker"; }
            @Override public double score(String proposal, List<String> all) {
                return proposal.equals(all.get(0)) ? 1.0 : 0.0;
            }
        };
    }

    /* ----------------------- constructor ----------------------- */

    @Test
    void constructorRejectsEmptyCritics() {
        assertThrows(IllegalArgumentException.class,
                () -> new CritiqueStrategy<String>(List.of()));
    }

    @Test
    void constructorRejectsNullCritics() {
        assertThrows(IllegalArgumentException.class,
                () -> new CritiqueStrategy<String>(null));
    }

    /* ----------------------- basic flow ----------------------- */

    @Test
    void winnerIsProposalWithHighestTotalScore() {
        // Use a single "yes-fan" critic to keep the test simple.
        CritiqueStrategy<String> s = new CritiqueStrategy<>(List.of(
                new Critic<String>() {
                    @Override public String name() { return "yes-fan"; }
                    @Override public double score(String p, List<String> all) {
                        return p.equals("yes") ? 1.0 : 0.0;
                    }
                }));
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "yes"), fixed("b", "no"), fixed("c", "maybe")));
        assertEquals("yes", r.winner());
        assertEquals(1, r.metadata().get("num_critics"));
        assertEquals(3, r.metadata().get("num_proposers"));
    }

    @Test
    void multipleCriticsScoresSum() {
        // Two critics both prefer "no": "no" total = 2.0, "yes" total = 0.
        CritiqueStrategy<String> s = new CritiqueStrategy<>(List.of(
                new Critic<String>() {
                    @Override public String name() { return "c1"; }
                    @Override public double score(String p, List<String> all) {
                        return p.equals("no") ? 1.0 : 0.0;
                    }
                },
                new Critic<String>() {
                    @Override public String name() { return "c2"; }
                    @Override public double score(String p, List<String> all) {
                        return p.equals("no") ? 1.0 : 0.0;
                    }
                }));
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "yes"), fixed("b", "no")));
        assertEquals("no", r.winner());
        Map<String, Double> totals = (Map<String, Double>) r.metadata().get("total_scores");
        assertEquals(2.0, totals.get("no"));
        assertEquals(0.0, totals.get("yes"));
    }

    @Test
    void firstCriticPicksFirstProposal() {
        // Single critic that always gives score=1 to the first proposal.
        CritiqueStrategy<String> s = new CritiqueStrategy<>(
                List.of(criticThatAlwaysPicksFirst()));
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "a-out"), fixed("b", "b-out"), fixed("c", "c-out")));
        assertEquals("a-out", r.winner());
    }

    /* ----------------------- metadata ----------------------- */

    @Test
    void perCriticMetadataExposesPerProposalScores() {
        CritiqueStrategy<String> s = new CritiqueStrategy<>(List.of(
                new Critic<String>() {
                    @Override public String name() { return "c1"; }
                    @Override public double score(String p, List<String> all) {
                        return p.equals("yes") ? 0.8 : 0.2;
                    }
                }));
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "yes"), fixed("b", "no")));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Double>> perCritic =
                (Map<String, Map<String, Double>>) r.metadata().get("per_critic");
        assertNotNull(perCritic);
        assertTrue(perCritic.containsKey("c1"));
        assertEquals(0.8, perCritic.get("c1").get("yes"));
        assertEquals(0.2, perCritic.get("c1").get("no"));
    }

    /* ----------------------- error containment ----------------------- */

    @Test
    void criticThrowingIsContained() {
        // A critic that throws must not crash the orchestrator; the
        // proposal still gets a 0.0 score from the broken critic.
        CritiqueStrategy<String> s = new CritiqueStrategy<>(List.of(
                new Critic<String>() {
                    @Override public String name() { return "broken"; }
                    @Override public double score(String p, List<String> all) {
                        throw new RuntimeException("boom");
                    }
                },
                criticThatAlwaysPicksFirst()));
        EnsembleResult<String> r = s.run("q",
                List.of(fixed("a", "a-out"), fixed("b", "b-out")));
        // Broken critic scores 0 for both. First-picker scores 1 for "a-out".
        // Total: a-out = 1, b-out = 0. "a-out" wins.
        assertEquals("a-out", r.winner());
    }

    @Test
    void proposersReceiveEmptyPeers() {
        // Critique strategy is NOT a debate; proposers see empty peers.
        java.util.concurrent.atomic.AtomicReference<List<String>> seenPeers =
                new java.util.concurrent.atomic.AtomicReference<>();
        AgentFn<String> spy = new AgentFn<>() {
            @Override public String name() { return "spy"; }
            @Override public String respond(String prompt, List<String> peers) {
                seenPeers.set(peers);
                return "x";
            }
        };
        CritiqueStrategy<String> s = new CritiqueStrategy<>(List.of(
                new Critic<String>() {
                    @Override public String name() { return "c"; }
                    @Override public double score(String p, List<String> all) { return 1.0; }
                }));
        s.run("q", List.of(spy, fixed("b", "y")));
        assertEquals(List.of(), seenPeers.get());
    }

    /* ----------------------- accessors ----------------------- */

    @Test
    void criticsAccessor() {
        List<Critic<String>> critics = List.of(
                new Critic<String>() {
                    @Override public String name() { return "c1"; }
                    @Override public double score(String p, List<String> all) { return 0; }
                });
        CritiqueStrategy<String> s = new CritiqueStrategy<>(critics);
        assertEquals(1, s.critics().size());
        assertEquals("c1", s.critics().get(0).name());
    }
}
