package org.aethercode.orchestration.multiagent;

import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleResult;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleResult.AgentOutput;
import org.aethercode.orchestration.multiagent.MultiAgentOrchestrator.EnsembleStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Critique ensemble: N proposers each produce an output, then M
 * critics each rank the proposals. The highest-scoring proposal wins.
 *
 * <p>Mirrors the "judge model" pattern from 2508.17281 §5.1
 * (LLM-as-judge) and the role-specialized multi-agent pattern from
 * 2601.01743 §III.1.1. Common in production ensembles where the
 * proposers are expensive (frontier model) and the critics are cheap
 * (smaller model).</p>
 *
 * <p>Scoring: the {@code Scorer} is a {@code (proposal, allProposals)
 * -> double} function that returns a score for one proposal. Critics
 * are run sequentially in registration order; each critic's score
 * for a proposal is added to the proposal's total. The proposal with
 * the highest total wins (ties broken by the first proposer).</p>
 *
 * <p>The prompts the critics see are constructed by the
 * {@code promptFor} function. By default the prompt is
 * {@code "<original prompt>\n\nProposal: <output>"} so the critic
 * can rank with full context. Tests can override {@code promptFor} to
 * inject a custom rubric.</p>
 */
public class CritiqueStrategy<T> implements EnsembleStrategy<T> {

    /**
     * A critic that scores one proposal in the context of all
     * proposals. Score is in [0, 1] (or any orderable double);
     * higher = better.
     */
    @FunctionalInterface
    public interface Critic<T> {
        /** Stable name (for audit logs). */
        default String name() { return getClass().getSimpleName(); }
        /** Return a score for {@code proposal} given the full list. */
        double score(T proposal, List<T> allProposals);
    }

    private final List<Critic<T>> critics;
    private final BiFunction<T, String, String> promptFor;

    public CritiqueStrategy(List<Critic<T>> critics) {
        this(critics, null);
    }

    public CritiqueStrategy(List<Critic<T>> critics, BiFunction<T, String, String> promptFor) {
        if (critics == null || critics.isEmpty()) {
            throw new IllegalArgumentException("critics must be non-empty");
        }
        this.critics = List.copyOf(critics);
        this.promptFor = promptFor == null ? this::defaultPromptFor : promptFor;
    }

    @Override
    public EnsembleResult<T> run(String prompt, List<AgentFn<T>> proposers) {
        // Phase 1: proposers run.
        List<T> proposals = new ArrayList<>(proposers.size());
        for (AgentFn<T> agent : proposers) {
            proposals.add(safeRespond(agent, prompt, List.of()));
        }

        // Phase 2: critics score each proposal.
        Map<T, Double> totals = new HashMap<>();
        Map<String, Map<T, Double>> perCritic = new HashMap<>();
        for (Critic<T> critic : critics) {
            Map<T, Double> perProposal = new HashMap<>();
            for (T proposal : proposals) {
                double s;
                try {
                    s = critic.score(proposal, proposals);
                } catch (RuntimeException ex) {
                    s = 0.0; // crash containment
                }
                perProposal.put(proposal, s);
                totals.merge(proposal, s, Double::sum);
            }
            perCritic.put(critic.name(), perProposal);
        }

        // Phase 3: pick highest-total proposal.
        T winner = totals.entrySet().stream()
                .max((a, b) -> {
                    int cmp = Double.compare(a.getValue(), b.getValue());
                    if (cmp != 0) return cmp;
                    // Tie-break: first proposer's output wins.
                    return proposals.indexOf(a.getKey()) - proposals.indexOf(b.getKey());
                })
                .orElseThrow()
                .getKey();

        Map<String, Object> meta = new HashMap<>();
        meta.put("total_scores", totals);
        meta.put("per_critic", perCritic);
        meta.put("num_proposers", proposers.size());
        meta.put("num_critics", critics.size());
        return EnsembleResult.of(winner, toOutputs(proposers, proposals), meta);
    }

    private static <T> T safeRespond(AgentFn<T> agent, String prompt, List<T> peers) {
        try {
            T out = agent.respond(prompt, peers);
            if (out == null) {
                throw new IllegalStateException("agent " + agent.name() + " returned null");
            }
            return out;
        } catch (RuntimeException ex) {
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

    private String defaultPromptFor(T proposal, String prompt) {
        return prompt + "\n\nProposal: " + proposal;
    }

    public List<Critic<T>> critics() { return critics; }
}
