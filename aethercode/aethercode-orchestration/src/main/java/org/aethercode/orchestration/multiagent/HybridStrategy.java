package org.aethercode.orchestration.multiagent;

import org.aethercode.orchestration.verifier.Verifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid multi-agent strategy: <b>decentralized execution +
 * centralized verification</b>.
 *
 * <p>Mirrors the "Hybrid" architecture in
 * arXiv:2512.08296 "Towards a Science of Scaling Agent Systems"
 * (Kim et al., 2025). Two phases:</p>
 *
 * <ol>
 *   <li><b>Decentralized execution</b> — N agents run in parallel
 *       on the same prompt. They do not see each other's outputs
 *       (true parallel).</li>
 *   <li><b>Centralized verification</b> — a single
 *       {@link Verifier} checks each output. The first output that
 *       passes is the winner; if all fail, the one with the
 *       <i>lowest severity</i> wins (defensive fallback).</li>
 * </ol>
 *
 * <p>Per the paper's findings, Hybrid inherits the error
 * containment of Centralized (4.4× amplification, not 17.2×)
 * while benefiting from Independent's parallel speed on
 * decomposable tasks.</p>
 */
public final class HybridStrategy<T>
        implements MultiAgentOrchestrator.EnsembleStrategy<T> {

    private final Verifier<T> verifier;

    public HybridStrategy(Verifier<T> verifier) {
        if (verifier == null) {
            throw new IllegalArgumentException("verifier must be non-null");
        }
        this.verifier = verifier;
    }

    public Verifier<T> verifier() { return verifier; }

    @Override
    public MultiAgentOrchestrator.EnsembleResult<T> run(String prompt, List<AgentFn<T>> agents) {
        if (prompt == null) {
            throw new IllegalArgumentException("prompt must be non-null");
        }
        if (agents == null || agents.isEmpty()) {
            throw new IllegalArgumentException("agents must be non-empty");
        }
        // Phase 1: decentralized parallel execution.
        List<MultiAgentOrchestrator.EnsembleResult.AgentOutput<T>> perAgent =
                new ArrayList<>(agents.size());
        for (AgentFn<T> a : agents) {
            T out = a.respond(prompt, List.of());
            perAgent.add(new MultiAgentOrchestrator.EnsembleResult.AgentOutput<>(
                    a.name(), out));
        }
        // Phase 2: centralized verification. First-pass wins.
        T winner = null;
        String winnerName = null;
        int winnerIdx = -1;
        // Track the best fallback (lowest severity) in case all fail.
        Verifier.VerificationResult bestResult = null;
        int bestIdx = -1;
        for (int i = 0; i < perAgent.size(); i++) {
            MultiAgentOrchestrator.EnsembleResult.AgentOutput<T> ao = perAgent.get(i);
            Verifier.VerificationResult r;
            try {
                r = verifier.verify(ao.output());
            } catch (RuntimeException ex) {
                r = Verifier.VerificationResult.fail(Verifier.Severity.BLOCK,
                        "verifier crashed: " + ex.getMessage());
            }
            if (r.passed()) {
                winner = ao.output();
                winnerName = ao.agentName();
                winnerIdx = i;
                bestResult = r;
                break;
            }
            if (bestResult == null || r.severity().ordinal() < bestResult.severity().ordinal()) {
                bestResult = r;
                bestIdx = i;
            }
        }
        if (winner == null) {
            // All failed — return the lowest-severity fallback.
            winner = perAgent.get(bestIdx).output();
            winnerName = perAgent.get(bestIdx).agentName();
            winnerIdx = bestIdx;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("strategy", "hybrid");
        meta.put("verifier", verifier.name());
        meta.put("winnerIndex", winnerIdx);
        meta.put("agents", perAgent.size());
        meta.put("firstPassed", bestResult != null && bestResult.passed());
        return MultiAgentOrchestrator.EnsembleResult.of(winner, perAgent, meta);
    }
}
