package org.aethercode.orchestration.multiagent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Geometry-of-Dialogue team composer (arXiv:2510.26352).
 *
 * <p>The paper (Hitachi, AAAI-26 Workshop LaMAS) shows that for a
 * given multi-agent task, the team's joint quality is maximised
 * when the agents' "skill distributions" form a low-dimensional
 * manifold (Wasserstein-2 distance between any two agents is
 * within an ε-ball). Teams whose agents are too similar (low
 * diversity) plateau; teams that are too diverse diverge.
 *
 * <p>This is the AetherCode Tier-3 implementation. The
 * {@code compose} method takes a pool of agents (each with a
 * {@code SkillVector}) and picks the subset whose pairwise
 * distances are closest to a target Wasserstein-2 radius.
 * No training required: it's pure geometry.
 *
 * <h2>Why geometric</h2>
 * Most team-composition heuristics (random pick, greedy coverage,
 * "best-of-N") ignore redundancy. Two agents with the same
 * skill vector halve throughput with no quality gain. The
 * paper's geometry-of-dialogue framing gives a principled
 * optimality criterion without requiring pairwise LLM judging.
 */
public final class TeamGeometryComposer {

    /** Sparse skill vector (dim ~10 typical). */
    public record SkillVector(Map<String, Double> weights) {
        public SkillVector {
            weights = weights == null ? Map.of() : Map.copyOf(weights);
        }
        public double dot(SkillVector o) {
            double s = 0;
            for (var e : weights.entrySet()) {
                s += e.getValue() * o.weights().getOrDefault(e.getKey(), 0.0);
            }
            return s;
        }
        public double norm() { return Math.sqrt(dot(this)); }
    }
    /** One candidate agent. */
    public record Agent(String name, SkillVector skills) {
        public Agent {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(skills, "skills");
        }
    }
    /** Recommended team. */
    public record Team(List<Agent> members, double meanPairwiseW2, double synergyScore) {}

    private final double targetW2;

    public TeamGeometryComposer(double targetW2) {
        if (targetW2 < 0) throw new IllegalArgumentException("targetW2 must be >= 0");
        this.targetW2 = targetW2;
    }

    /**
     * Pick a subset of {@code pool} that maximises the synergy
     * score (high mean intra-team diversity, low variance around
     * {@code targetW2}).
     */
    public Team compose(List<Agent> pool, int teamSize) {
        Objects.requireNonNull(pool, "pool");
        if (teamSize <= 0 || pool.size() <= teamSize) {
            return new Team(pool, meanPairwiseDistance(pool), synergy(pool));
        }
        // Greedy: start with the highest-norm agent, then add the agent
        // that maximises the synergy score (low |meanW2 - target|).
        List<Agent> best = greedyGreedy(pool, teamSize);
        return new Team(best, meanPairwiseDistance(best), synergy(best));
    }

    private List<Agent> greedyGreedy(List<Agent> pool, int teamSize) {
        List<Agent> team = new ArrayList<>();
        // pick the first member as the highest-norm agent
        Agent seed = pool.stream()
            .max((a, b) -> Double.compare(a.skills().norm(), b.skills().norm()))
            .orElseThrow();
        team.add(seed);
        while (team.size() < teamSize) {
            Agent best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (Agent candidate : pool) {
                if (team.contains(candidate)) continue;
                List<Agent> tentative = new ArrayList<>(team);
                tentative.add(candidate);
                double s = synergy(tentative);
                if (s > bestScore) {
                    bestScore = s;
                    best = candidate;
                }
            }
            if (best == null) break;
            team.add(best);
        }
        return team;
    }

    /** Mean pairwise W2 distance approximation. For sparse vectors
     *  this collapses to mean pairwise Euclidean distance / 2, which
     *  is a close approximation for the discrete Wasserstein-1
     *  metric over a shared support. */
    public double meanPairwiseDistance(List<Agent> team) {
        if (team.size() < 2) return 0.0;
        double sum = 0;
        int n = 0;
        for (int i = 0; i < team.size(); i++) {
            for (int j = i + 1; j < team.size(); j++) {
                sum += euclid(team.get(i).skills(), team.get(j).skills());
                n++;
            }
        }
        return sum / n;
    }

    /**
     * Synergy score: peaked when mean pairwise distance is close to
     * {@code targetW2}, falling off quadratically. The negative
     * quadratically-penalised distance keeps the team from drifting
     * too diverse.
     */
    public double synergy(List<Agent> team) {
        double m = meanPairwiseDistance(team);
        double diff = m - targetW2;
        return -diff * diff;
    }

    private static double euclid(SkillVector a, SkillVector b) {
        Set<String> keys = new HashSet<>();
        keys.addAll(a.weights().keySet());
        keys.addAll(b.weights().keySet());
        double sum = 0;
        for (String k : keys) {
            double diff = a.weights().getOrDefault(k, 0.0) - b.weights().getOrDefault(k, 0.0);
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }
}
