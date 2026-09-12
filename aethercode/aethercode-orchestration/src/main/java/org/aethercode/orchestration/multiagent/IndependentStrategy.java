package org.aethercode.orchestration.multiagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Independent multi-agent strategy: N agents run in parallel
 * with <b>no inter-agent communication</b>. The first agent's
 * output is returned as the winner without any voting, critique,
 * or central verification.
 *
 * <p>Mirrors the "Independent" architecture in
 * arXiv:2512.08296 "Towards a Science of Scaling Agent Systems"
 * (Kim et al., 2025). Per the paper's findings:</p>
 * <ul>
 *   <li>Independent agents amplify errors <b>17.2×</b> through
 *       unchecked propagation (vs. 4.4× for Centralized).</li>
 *   <li>Despite the error-amplification risk, Independent beats
 *       Centralized on <b>dynamic web navigation</b> tasks
 *       (+9.2% vs. +0.2%).</li>
 *   <li>Use it when the task is dynamic, parallelizable, and
 *       time-sensitive — the cost of coordination exceeds the
 *       benefit of error containment.</li>
 * </ul>
 *
 * <p>The strategy is intentionally simple: no voting, no
 * verification, no debate. The audit log records the order
 * agents ran and which one was picked.</p>
 */
public final class IndependentStrategy<T>
        implements MultiAgentOrchestrator.EnsembleStrategy<T> {

    /** Mode for picking the winner from the N independent runs. */
    public enum PickMode {
        /** Pick the first agent (deterministic, fastest). */
        FIRST,
        /** Pick a random agent (introduces non-determinism). */
        RANDOM,
        /** Pick the last agent (deterministic, latest). */
        LAST
    }

    private final PickMode pick;

    public IndependentStrategy() {
        this(PickMode.FIRST);
    }

    public IndependentStrategy(PickMode pick) {
        if (pick == null) throw new IllegalArgumentException("pick must be non-null");
        this.pick = pick;
    }

    public PickMode pick() { return pick; }

    @Override
    public MultiAgentOrchestrator.EnsembleResult<T> run(String prompt, List<AgentFn<T>> agents) {
        if (prompt == null) {
            throw new IllegalArgumentException("prompt must be non-null");
        }
        if (agents == null || agents.isEmpty()) {
            throw new IllegalArgumentException("agents must be non-empty");
        }
        List<MultiAgentOrchestrator.EnsembleResult.AgentOutput<T>> perAgent =
                new ArrayList<>(agents.size());
        for (AgentFn<T> a : agents) {
            T out = a.respond(prompt, List.of()); // peers always empty: no communication
            perAgent.add(new MultiAgentOrchestrator.EnsembleResult.AgentOutput<>(
                    a.name(), out));
        }
        // Pick the winner.
        int idx;
        switch (pick) {
            case FIRST: idx = 0; break;
            case LAST:  idx = perAgent.size() - 1; break;
            case RANDOM:
            default:    idx = (int) (Math.random() * perAgent.size()); break;
        }
        T winner = perAgent.get(idx).output();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("strategy", "independent");
        meta.put("pick", pick.name());
        meta.put("winnerIndex", idx);
        meta.put("agents", perAgent.size());
        return MultiAgentOrchestrator.EnsembleResult.of(winner, perAgent, meta);
    }
}
