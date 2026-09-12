package org.aethercode.orchestration.multiagent;

import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleResult;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleResult.AgentOutput;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Debate ensemble: N agents iterate over the same {@code prompt} for
 * up to {@code maxRounds} rounds. Each round, every agent sees the
 * other agents' outputs from the previous round (as {@code peers}).
 * The ensemble's winner is the majority output in the last round
 * (or earlier, if all agents agree before {@code maxRounds}).
 *
 * <p>Mirrors the debate / discussion patterns from
 * 2601.01743 §III.1.1 (multi-agent systems with iterative
 * refinement) and the "self-consistency" pattern from
 * 2508.17281 §5.1 (multiple samples → majority vote). Debate
 * differs from self-consistency in that each agent sees the others'
 * outputs and can refine, not just vote.</p>
 *
 * <p>Convergence: the loop stops early if all agents in the current
 * round produce the same output ({@code consensus = true}). This is
 * the "the agents have agreed, no point in more rounds" case.</p>
 */
public class DebateStrategy<T> implements EnsembleStrategy<T> {

    private final int maxRounds;

    public DebateStrategy() { this(3); }

    public DebateStrategy(int maxRounds) {
        if (maxRounds < 1) {
            throw new IllegalArgumentException("maxRounds must be >= 1");
        }
        this.maxRounds = maxRounds;
    }

    @Override
    public EnsembleResult<T> run(String prompt, List<AgentFn<T>> agents) {
        List<T> current = new ArrayList<>(agents.size());
        // First round: no peers yet.
        for (AgentFn<T> agent : agents) {
            current.add(safeRespond(agent, prompt, List.of()));
        }

        List<AgentOutput<T>> lastOutputs = toOutputs(agents, current);
        int round = 1;
        boolean consensus = allEqual(current);

        while (!consensus && round < maxRounds) {
            round++;
            List<T> next = new ArrayList<>(agents.size());
            for (int i = 0; i < agents.size(); i++) {
                // Peers = everyone else's CURRENT output, not the new
                // one being produced. (Stabilises the loop: agent i
                // sees round-N outputs from everyone except itself.)
                List<T> peers = new ArrayList<>(current);
                peers.remove(i);
                next.add(safeRespond(agents.get(i), prompt, peers));
            }
            current = next;
            lastOutputs = toOutputs(agents, current);
            consensus = allEqual(current);
        }

        // Pick winner: the most common current output. Ties broken
        // by the first agent's output.
        T winner = pickMajority(current);

        Map<String, Object> meta = new HashMap<>();
        meta.put("rounds", round);
        meta.put("consensus", consensus);
        meta.put("max_rounds", maxRounds);
        meta.put("early_stopped", consensus && round < maxRounds);
        return EnsembleResult.of(winner, lastOutputs, meta);
    }

    private static <T> T safeRespond(AgentFn<T> agent, String prompt, List<T> peers) {
        try {
            T out = agent.respond(prompt, peers);
            if (out == null) {
                throw new IllegalStateException("agent " + agent.name() + " returned null");
            }
            return out;
        } catch (RuntimeException ex) {
            // Surface the failure in the output so the voter can see it.
            // This is a deterministic stand-in; production would
            // surface the error to the user.
            @SuppressWarnings("unchecked")
            T sentinel = (T) ("<<agent-failed:" + agent.name() + ":" + ex.getClass().getSimpleName() + ">>");
            return sentinel;
        }
    }

    private static <T> List<AgentOutput<T>> toOutputs(List<AgentFn<T>> agents, List<T> values) {
        List<AgentOutput<T>> out = new ArrayList<>(agents.size());
        for (int i = 0; i < agents.size(); i++) {
            out.add(new AgentOutput<>(agents.get(i).name(), values.get(i)));
        }
        return out;
    }

    private static <T> boolean allEqual(List<T> values) {
        if (values.isEmpty()) return true;
        T first = values.get(0);
        for (int i = 1; i < values.size(); i++) {
            if (!java.util.Objects.equals(first, values.get(i))) return false;
        }
        return true;
    }

    private static <T> T pickMajority(List<T> values) {
        Map<T, Integer> tally = new HashMap<>();
        for (T v : values) tally.merge(v, 1, Integer::sum);
        // First-wins tie-break: walk values in order, return the one
        // with the highest count that we see first.
        Set<T> seen = new HashSet<>();
        T best = null;
        int bestCount = -1;
        for (T v : values) {
            if (seen.add(v)) {
                int c = tally.get(v);
                if (c > bestCount) {
                    best = v;
                    bestCount = c;
                }
            }
        }
        return best;
    }

    public int maxRounds() { return maxRounds; }
}
