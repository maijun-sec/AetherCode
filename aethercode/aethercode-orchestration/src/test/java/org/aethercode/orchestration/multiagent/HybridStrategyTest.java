package org.aethercode.orchestration.multiagent;

import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleResult;
import org.aethercode.orchestration.verifier.Verifier;
import org.aethercode.orchestration.verifier.Verifier.VerificationResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-orchestration: Hybrid strategy (paper 2512.08296 "Hybrid"
 * architecture — decentralized execution + centralized verify).
 */
class HybridStrategyTest {

    /** Convenience: build a stub AgentFn with a given name. */
    private static AgentFn<String> stub(String name, java.util.function.Function<String, String> body) {
        return new AgentFn<>() {
            @Override public String name() { return name; }
            @Override public String respond(String prompt, List<String> peers) {
                return body.apply(prompt);
            }
        };
    }

    @Test
    void firstPassingAgentWins() {
        // Agent "a" fails, "b" passes, "c" passes.
        // Hybrid should pick "b" (the first pass).
        Verifier<String> v = new Verifier<>() {
            @Override public String name() { return "v"; }
            @Override public VerificationResult verify(String s) {
                return s.equals("B") ? VerificationResult.pass() : VerificationResult.fail(Severity.BLOCK, "no");
            }
        };
        HybridStrategy<String> h = new HybridStrategy<>(v);
        List<AgentFn<String>> agents = List.of(
                stub("a", (p) -> "A"),
                stub("b", (p) -> "B"),
                stub("c", (p) -> "C")
        );
        EnsembleResult<String> r = h.run("p", agents);
        assertEquals("B", r.winner());
        assertEquals(1, r.metadata().get("winnerIndex"));
    }

    @Test
    void allFailingPicksLowestSeverity() {
        // All 3 agents fail; the BLOCK-severity output is worse
        // than the WARN-severity one. Hybrid should pick the
        // WARN agent as a defensive fallback.
        Verifier<String> v = new Verifier<>() {
            @Override public String name() { return "v"; }
            @Override public VerificationResult verify(String s) {
                if (s.equals("warn-agent")) return VerificationResult.fail(Severity.WARN, "warn");
                return VerificationResult.fail(Severity.BLOCK, "block");
            }
        };
        HybridStrategy<String> h = new HybridStrategy<>(v);
        List<AgentFn<String>> agents = List.of(
                stub("block-1", (p) -> "block-1"),
                stub("warn-agent", (p) -> "warn-agent"),
                stub("block-2", (p) -> "block-2")
        );
        EnsembleResult<String> r = h.run("p", agents);
        assertEquals("warn-agent", r.winner());
        // All failed, so firstPassed is false.
        assertEquals(false, r.metadata().get("firstPassed"));
    }

    @Test
    void rejectsNullVerifier() {
        assertThrows(IllegalArgumentException.class,
                () -> new HybridStrategy<>(null));
    }

    @Test
    void rejectsEmptyAgents() {
        Verifier<String> v = new Verifier<>() {
            @Override public String name() { return "v"; }
            @Override public VerificationResult verify(String s) { return VerificationResult.pass(); }
        };
        HybridStrategy<String> h = new HybridStrategy<>(v);
        assertThrows(IllegalArgumentException.class,
                () -> h.run("p", List.of()));
    }

    @Test
    void survivingVerifierCrashDoesNotPoisonLoop() {
        // A verifier that throws on some inputs must not break
        // the strategy. Hybrid wraps the exception in a BLOCK
        // failure and moves on.
        Verifier<String> v = new Verifier<>() {
            @Override public String name() { return "v"; }
            @Override public VerificationResult verify(String s) {
                if (s.equals("crash")) {
                    throw new RuntimeException("verifier boom");
                }
                if (s.equals("B")) {
                    return VerificationResult.pass();
                }
                return VerificationResult.fail(Severity.BLOCK, "no");
            }
        };
        HybridStrategy<String> h = new HybridStrategy<>(v);
        List<AgentFn<String>> agents = List.of(
                stub("a", (p) -> "crash"),
                stub("b", (p) -> "B")
        );
        EnsembleResult<String> r = h.run("p", agents);
        assertEquals("B", r.winner(), "verifier crash on agent 'a' is contained");
    }

    @Test
    void hybridMetadataRecordsVerifierName() {
        Verifier<String> v = new Verifier<>() {
            @Override public String name() { return "my-v"; }
            @Override public VerificationResult verify(String s) { return VerificationResult.pass(); }
        };
        HybridStrategy<String> h = new HybridStrategy<>(v);
        List<AgentFn<String>> agents = List.of(
                stub("a", (p) -> "A")
        );
        EnsembleResult<String> r = h.run("p", agents);
        assertEquals("my-v", r.metadata().get("verifier"));
        assertEquals("hybrid", r.metadata().get("strategy"));
    }

    @Test
    void hybridIsDistinctFromVoteAndCritique() {
        // Hybrid differs from Vote (no verification) and Critique
        // (no fallback when all fail). Lock the contract.
        Verifier<String> v = new Verifier<>() {
            @Override public String name() { return "v"; }
            @Override public VerificationResult verify(String s) {
                return s.equals("A") ? VerificationResult.pass() : VerificationResult.fail(Severity.BLOCK, "no");
            }
        };
        HybridStrategy<String> h = new HybridStrategy<>(v);
        // Same 3 agents: a passes, b and c fail.
        // Vote would pick the most-common; here b == c so it
        // would pick b or c. Hybrid picks a.
        List<AgentFn<String>> agents = List.of(
                stub("a", (p) -> "A"),
                stub("b", (p) -> "B"),
                stub("c", (p) -> "B")
        );
        EnsembleResult<String> r = h.run("p", agents);
        assertEquals("A", r.winner(),
                "Hybrid should pick the verified-A over vote-winner B");
    }

    @Test
    void hybridSurvivesVerifierNameStability() {
        // The verifier name is part of the wire format (audit
        // log consumers grep on it). Lock the contract.
        Verifier<String> v = new Verifier<>() {
            @Override public String name() { return "stable-v"; }
            @Override public VerificationResult verify(String s) { return VerificationResult.pass(); }
        };
        assertEquals("stable-v", v.name());
        // The strategy stores the verifier name in metadata.
        HybridStrategy<String> h = new HybridStrategy<>(v);
        EnsembleResult<String> r = h.run("p", List.of(
                stub("a", (p) -> "A")));
        assertEquals("stable-v", r.metadata().get("verifier"));
        assertNotNull(r.winner());
    }
}
