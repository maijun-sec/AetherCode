package org.aethercode.orchestration.multiagent;

import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleResult;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Paper 2511.09030 MAKER-style voting.
 * <p>
 * First-to-ahead-by-k voting: N agents each produce samples, and the first
 * candidate that leads by k votes wins. If k is reached early, the loop exits
 * (saves cost). The paper shows that k = Θ(ln s) where s is the task length,
 * so the expected cost grows only logarithmically with s.
 * <p>
 * This is generalization of {@link VoteStrategy} (which is a special case with
 * k = 1 and total sample = N).
 */
public final class FirstToAheadByKVoting<T> implements EnsembleStrategy<T> {

    private final int k;
    private final int samplesPerAgent;
    private final Supplier<T> agentSampler;

    /**
     * @param k vote margin required to win
     * @param samplesPerAgent max samples per agent before giving up
     * @param agentSampler function that produces a fresh sample
     */
    public FirstToAheadByKVoting(int k, int samplesPerAgent, Supplier<T> agentSampler) {
        if (k < 1) throw new IllegalArgumentException("k must be >= 1");
        if (samplesPerAgent < 1) throw new IllegalArgumentException("samplesPerAgent must be >= 1");
        this.k = k;
        this.samplesPerAgent = samplesPerAgent;
        this.agentSampler = Objects.requireNonNull(agentSampler, "agentSampler");
    }

    @Override
    public EnsembleResult<T> run(String prompt, List<AgentFn<T>> agents) {
        Objects.requireNonNull(agents, "agents");
        if (agents.isEmpty()) throw new IllegalArgumentException("agents empty");

        Map<T, Integer> voteCounts = new HashMap<>();
        List<T> allSamples = new ArrayList<>();
        int totalSamples = 0;

        int maxIterations = agents.size() * samplesPerAgent;
        T winner = null;
        for (int i = 0; i < maxIterations; i++) {
            AgentFn<T> agent = agents.get(i % agents.size());
            T sample = agent.respond(prompt, List.of());
            allSamples.add(sample);
            totalSamples++;
            voteCounts.merge(sample, 1, Integer::sum);

            // Check if any candidate leads by k
            T top = null;
            int topCount = 0;
            int runnerUpCount = 0;
            for (var entry : voteCounts.entrySet()) {
                if (entry.getValue() > topCount) {
                    runnerUpCount = topCount;
                    topCount = entry.getValue();
                    top = entry.getKey();
                } else if (entry.getValue() > runnerUpCount) {
                    runnerUpCount = entry.getValue();
                }
            }
            if (top != null && (topCount - runnerUpCount) >= k) {
                winner = top;
                break;
            }
        }
        if (winner == null && !voteCounts.isEmpty()) {
            // Fallback: pick the most-voted
            int max = -1;
            for (var e : voteCounts.entrySet()) {
                if (e.getValue() > max) {
                    max = e.getValue();
                    winner = e.getKey();
                }
            }
        }

        Map<String, Object> meta = new HashMap<>();
        meta.put("strategy", "first-to-ahead-by-k");
        meta.put("k", k);
        meta.put("totalSamples", totalSamples);
        meta.put("earlyStop", winner != null && totalSamples < maxIterations);
        // Wrap all samples as AgentOutput entries (agentName="sample-i")
        List<EnsembleResult.AgentOutput<T>> perAgent = new ArrayList<>();
        for (int i = 0; i < allSamples.size(); i++) {
            perAgent.add(new EnsembleResult.AgentOutput<>("sample-" + i, allSamples.get(i)));
        }
        return new EnsembleResult<>(winner, perAgent, meta);
    }

    @Override
    public String toString() {
        return "first-to-ahead-by-k";
    }

    /** Recommended k for task of length s: k = ceil(ln(s)) (paper 2511.09030). */
    public static int recommendedKForTaskLength(int s) {
        if (s < 2) return 1;
        return (int) Math.ceil(Math.log(s));
    }
}
