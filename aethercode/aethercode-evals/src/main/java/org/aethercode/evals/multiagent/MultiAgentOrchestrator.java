package org.aethercode.evals.multiagent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run N agents on the same {@code prompt}, optionally debating /
 * critiquing each other, and combine their outputs into one final
 * answer.
 *
 * <p>Mirrors the multi-agent patterns surveyed in paper
 * 2601.01743 §III.1.1 (multi-agent systems and collaboration
 * frameworks — MetaGPT, CAMEL, AgentBoard) and 2508.17281 §1.1.1.
 * Three orchestration strategies are supported:</p>
 *
 * <ul>
 *   <li>{@link VoteStrategy} — N agents run, the most common output
 *       wins (majority / unanimous).</li>
 *   <li>{@link DebateStrategy} — N agents run iteratively, each round
 *       sees the previous round's outputs as {@code peers}, until
 *       convergence or budget exhausted.</li>
 *   <li>{@link CritiqueStrategy} — N proposers + M critics, the
 *       highest-ranked proposal wins.</li>
 * </ul>
 *
 * <p>For all three the result is the same shape: one
 * {@link EnsembleResult} that carries the winning output, all
 * per-agent outputs, and an audit-trail summary.</p>
 */
public class MultiAgentOrchestrator<T> {

    private final String name;
    private final List<AgentFn<T>> agents;
    private final EnsembleStrategy<T> strategy;

    public MultiAgentOrchestrator(String name, List<AgentFn<T>> agents,
                                 EnsembleStrategy<T> strategy) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        if (agents == null || agents.isEmpty()) {
            throw new IllegalArgumentException("agents must be non-empty");
        }
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must be non-null");
        }
        this.name = name;
        this.agents = List.copyOf(agents);
        this.strategy = strategy;
    }

    public EnsembleResult<T> run(String prompt) {
        if (prompt == null) {
            throw new IllegalArgumentException("prompt must be non-null");
        }
        return strategy.run(prompt, agents);
    }

    public String name() { return name; }
    public List<AgentFn<T>> agents() { return agents; }
    public EnsembleStrategy<T> strategy() { return strategy; }

    /**
     * The strategy is a small strategy-object so callers can swap
     * vote / debate / critique without changing the orchestrator.
     */
    @FunctionalInterface
    public interface EnsembleStrategy<T> {
        EnsembleResult<T> run(String prompt, List<AgentFn<T>> agents);
    }

    /**
     * Result of one ensemble run.
     *
     * <p>Always carries the winning output ({@link #winner()}) and the
     * raw per-agent outputs ({@link #perAgent()}). The strategy may
     * add its own metadata (round number for debate, critic scores
     * for critique, etc.) in {@link #metadata()}.</p>
     */
    public record EnsembleResult<T>(
            T winner,
            List<AgentOutput<T>> perAgent,
            Map<String, Object> metadata) {

        /** A single agent's output plus its stable name (for audit). */
        public record AgentOutput<T>(String agentName, T output) {
            public AgentOutput {
                if (agentName == null || agentName.isBlank()) {
                    throw new IllegalArgumentException("agentName must be non-blank");
                }
                if (output == null) {
                    throw new IllegalArgumentException("output must be non-null");
                }
            }
        }

        public static <T> EnsembleResult<T> of(T winner, List<AgentOutput<T>> perAgent) {
            return new EnsembleResult<>(winner, perAgent, new LinkedHashMap<>());
        }

        public static <T> EnsembleResult<T> of(T winner, List<AgentOutput<T>> perAgent,
                                              Map<String, Object> metadata) {
            return new EnsembleResult<>(winner, perAgent, new LinkedHashMap<>(metadata));
        }

        /** Names of all agents that produced an output. */
        public List<String> agentNames() {
            List<String> n = new ArrayList<>(perAgent.size());
            for (AgentOutput<T> a : perAgent) n.add(a.agentName());
            return n;
        }

        /** Map view: agent name → output. */
        public Map<String, T> byName() {
            Map<String, T> m = new LinkedHashMap<>();
            for (AgentOutput<T> a : perAgent) m.put(a.agentName(), a.output());
            return m;
        }
    }
}
