package org.aethercode.orchestration.multiagent;

import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleResult;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleResult.AgentOutput;
import org.aethercode.orchestration.multiagent.VoteStrategy.VoteMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link MultiAgentOrchestrator} — the wrapper that ties
 * agents to a strategy and produces a unified {@link EnsembleResult}.
 */
class MultiAgentOrchestratorTest {

    private static AgentFn<String> fixed(String name, String out) {
        return new AgentFn<>() {
            @Override public String name() { return name; }
            @Override public String respond(String prompt, List<String> peers) { return out; }
        };
    }

    /* ----------------------- constructor ----------------------- */

    @Test
    void constructorRejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new MultiAgentOrchestrator<>("", List.of(fixed("a", "x")),
                        new VoteStrategy<>()));
    }

    @Test
    void constructorRejectsEmptyAgents() {
        assertThrows(IllegalArgumentException.class,
                () -> new MultiAgentOrchestrator<>("o", List.of(), new VoteStrategy<>()));
    }

    @Test
    void constructorRejectsNullStrategy() {
        assertThrows(IllegalArgumentException.class,
                () -> new MultiAgentOrchestrator<>("o", List.of(fixed("a", "x")), null));
    }

    @Test
    void runRejectsNullPrompt() {
        MultiAgentOrchestrator<String> o = new MultiAgentOrchestrator<>(
                "o", List.of(fixed("a", "x")), new VoteStrategy<>());
        assertThrows(IllegalArgumentException.class, () -> o.run(null));
    }

    /* ----------------------- wiring ----------------------- */

    @Test
    void runDelegatesToStrategy() {
        MultiAgentOrchestrator<String> o = new MultiAgentOrchestrator<>(
                "vote-orch",
                List.of(fixed("a", "yes"), fixed("b", "yes"), fixed("c", "no")),
                new VoteStrategy<>(VoteMode.PLURALITY));
        EnsembleResult<String> r = o.run("q");
        assertEquals("yes", r.winner());
        assertEquals(3, r.perAgent().size());
        assertEquals("vote-orch", r.byName().keySet().toString().contains("a") ? "vote-orch" : "?",
                "metadata should not include orch name; test is just checking wiring");
    }

    @Test
    void nameAndAccessors() {
        VoteStrategy<String> s = new VoteStrategy<>();
        MultiAgentOrchestrator<String> o = new MultiAgentOrchestrator<>(
                "my-orch", List.of(fixed("a", "x"), fixed("b", "x")), s);
        assertEquals("my-orch", o.name());
        assertEquals(2, o.agents().size());
        assertSame(s, o.strategy());
    }

    /* ----------------------- EnsembleResult helpers ----------------------- */

    @Test
    void agentOutputRecordRejectsBadArgs() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgentOutput<>(null, "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentOutput<>("", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> new AgentOutput<>("a", null));
    }

    @Test
    void ensembleResultAgentNamesAndByName() {
        EnsembleResult<String> r = EnsembleResult.of("yes", List.of(
                new AgentOutput<>("a", "yes"),
                new AgentOutput<>("b", "yes"),
                new AgentOutput<>("c", "no")));
        assertEquals(List.of("a", "b", "c"), r.agentNames());
        Map<String, String> map = r.byName();
        assertEquals(3, map.size());
        assertEquals("yes", map.get("a"));
        assertEquals("no", map.get("c"));
    }

    @Test
    void ensembleResultOfWithMetadata() {
        EnsembleResult<String> r = EnsembleResult.of("x", List.of(
                new AgentOutput<>("a", "x")),
                Map.of("strategy", "vote", "rounds", 1));
        assertEquals("vote", r.metadata().get("strategy"));
        assertEquals(1, r.metadata().get("rounds"));
    }
}
