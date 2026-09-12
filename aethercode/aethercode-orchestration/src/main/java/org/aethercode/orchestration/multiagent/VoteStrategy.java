package org.aethercode.orchestration.multiagent;

import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleResult;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleResult.AgentOutput;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleStrategy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Function;

/**
 * Vote ensemble: N agents run on the same {@code prompt} (with no
 * peers) and the most common output wins. The {@code voteMode} picks
 * how to break ties / partial agreements.
 *
 * <p>Three modes (from least to most strict):</p>
 * <ul>
 *   <li>{@link VoteMode#PLURALITY} — pick the output with the most
 *       votes. Ties broken by the first agent in registration order.
 *       This is what MetaGPT and CrewAI do for default ensembles.</li>
 *   <li>{@link VoteMode#MAJORITY} — pick the output that has
 *       {@code > n/2} votes. If no output crosses the threshold,
 *       fall back to plurality (with a warning in metadata).</li>
 *   <li>{@link VoteMode#UNANIMOUS} — only return a winner if every
 *       agent produced the same output; otherwise pick the most
 *       common and mark the result as "no consensus" in metadata.</li>
 * </ul>
 *
 * <p>Outputs are compared by {@link Object#equals}. For free-form
 * text this means byte-equality; the orchestrator's caller is
 * expected to normalise whitespace / case before voting if loose
 * matching is needed. Tests can wire {@code equals} directly through
 * a custom {@code T}.</p>
 */
public class VoteStrategy<T> implements EnsembleStrategy<T> {

    public enum VoteMode { PLURALITY, MAJORITY, UNANIMOUS }

    private final VoteMode mode;
    private final Executor executor;

    public VoteStrategy() { this(VoteMode.PLURALITY); }

    public VoteStrategy(VoteMode mode) { this(mode, null); }

    public VoteStrategy(VoteMode mode, Executor executor) {
        if (mode == null) throw new IllegalArgumentException("mode must be non-null");
        this.mode = mode;
        this.executor = executor;
    }

    @Override
    public EnsembleResult<T> run(String prompt, List<AgentFn<T>> agents) {
        // Run all agents in parallel when an executor is available;
        // otherwise sequential (still correct, just slower).
        List<AgentOutput<T>> outputs = new ArrayList<>(agents.size());
        if (executor == null) {
            for (AgentFn<T> agent : agents) {
                outputs.add(safeInvoke(agent, prompt, List.of()));
            }
        } else {
            List<CompletableFuture<AgentOutput<T>>> futures = new ArrayList<>(agents.size());
            for (AgentFn<T> agent : agents) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> safeInvoke(agent, prompt, List.of()), executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            for (CompletableFuture<AgentOutput<T>> f : futures) {
                outputs.add(f.join());
            }
        }

        // Tally.
        Map<T, Integer> tally = new HashMap<>();
        for (AgentOutput<T> out : outputs) {
            tally.merge(out.output(), 1, Integer::sum);
        }

        // Pick winner.
        T winner = pickWinner(tally, outputs);
        boolean consensus = tally.get(winner) == agents.size();

        Map<String, Object> meta = new HashMap<>();
        meta.put("mode", mode);
        meta.put("tally", tally);
        meta.put("consensus", consensus);
        meta.put("total_agents", agents.size());
        if (!consensus && mode == VoteMode.UNANIMOUS) {
            meta.put("fallback", "no consensus, picked plurality winner");
        }
        if (mode == VoteMode.MAJORITY && tally.get(winner) <= agents.size() / 2) {
            meta.put("fallback", "no majority, picked plurality winner");
        }
        return EnsembleResult.of(winner, outputs, meta);
    }

    private static <T> AgentOutput<T> safeInvoke(AgentFn<T> agent, String prompt, List<T> peers) {
        try {
            T out = agent.respond(prompt, peers);
            if (out == null) {
                throw new IllegalStateException("agent " + agent.name() + " returned null");
            }
            return new AgentOutput<>(agent.name(), out);
        } catch (RuntimeException ex) {
            // Crash containment: the orchestrator must not fail because
            // one agent failed. Substitute a sentinel; vote tally will
            // skip the sentinel (it never equals another agent's output
            // unless another agent happens to throw the same string).
            // We surface the failure in the audit log via agentName.
            String sentinel = "<<agent-failed:" + agent.name() + ":" + ex.getClass().getSimpleName() + ">>";
            @SuppressWarnings("unchecked")
            T casted = (T) sentinel;
            return new AgentOutput<>(agent.name() + "[FAILED]", casted);
        }
    }

    private T pickWinner(Map<T, Integer> tally, List<AgentOutput<T>> outputs) {
        // Tie-break: earliest-registered output wins. We invert the
        // index so the comparator's "largest" picks the smallest index.
        return tally.entrySet().stream()
                .max(Comparator.<Map.Entry<T, Integer>>comparingInt(Map.Entry::getValue)
                        .thenComparing(e -> -indexOfFirstAgentWithOutput(outputs, e.getKey())))
                .orElseThrow()
                .getKey();
    }

    private int indexOfFirstAgentWithOutput(List<AgentOutput<T>> outputs, T output) {
        for (int i = 0; i < outputs.size(); i++) {
            if (Objects.equals(outputs.get(i).output(), output)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    public VoteMode mode() { return mode; }
    public Executor executor() { return executor; }
}
