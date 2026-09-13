package org.aethercode.orchestration.verifier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * WorFBench-style workflow evaluator (arXiv:2410.07869).
 *
 * <p>WorFBench introduces three levels of workflow-alignment scoring:
 * <ul>
 *   <li><b>Holistic</b> — exact end-to-end graph match (most strict)</li>
 *   <li><b>Subsequence</b> — longest contiguous run of matched nodes</li>
 *   <li><b>Subgraph</b> — node + edge set overlap (most forgiving)</li>
 * </ul>
 * The paper shows these decompose the "end-to-end success rate" into
 * three orthogonal failure modes: ordering bugs (subsequence), topology
 * bugs (subgraph), and full-pipeline bugs (holistic).
 *
 * <p>This class is the AetherCode Tier-3 implementation of the metric.
 * Workflows are represented as a list of step IDs and a list of
 * (from, to) edges. The evaluator computes all three scores plus a
 * composite.
 *
 * <h2>Why this is useful</h2>
 * AetherCode's planning layer ({@code GlobalPlan},
 * {@code HierarchicalExecutor}) produces plan graphs; this evaluator
 * lets a verification hook score a generated plan against a
 * reference without needing to execute either plan. Combined with
 * the {@code Verifier} framework it gives a paper-faithful
 * "graph-level" red flag.
 */
public final class WorkflowEval {

    /** A single workflow step (node). */
    public record Step(String id) {
        public Step { Objects.requireNonNull(id, "id"); }
    }
    /** A directed edge between two steps. */
    public record Edge(String from, String to) {
        public Edge { Objects.requireNonNull(from, "from"); Objects.requireNonNull(to, "to"); }
    }
    /** A workflow = ordered step list + edge list. */
    public record Workflow(List<Step> steps, List<Edge> edges) {
        public Workflow {
            steps = List.copyOf(steps);
            edges = edges == null ? List.of() : List.copyOf(edges);
        }
        public static Workflow of(String... ids) {
            List<Step> ss = new ArrayList<>(ids.length);
            for (String id : ids) ss.add(new Step(id));
            return new Workflow(ss, List.of());
        }
        public static Workflow of(List<Step> steps, List<Edge> edges) {
            return new Workflow(steps, edges);
        }
    }
    /** Triple of (holistic, subsequence, subgraph) plus composite. */
    public record Score(double holistic, double subsequence, double subgraph) {
        /** Composite: weighted average (paper uses 0.5 / 0.3 / 0.2). */
        public double composite() {
            return 0.5 * holistic + 0.3 * subsequence + 0.2 * subgraph;
        }
    }

    /**
     * Compute all three scores for a candidate workflow against a
     * reference. Both workflows must be non-empty; if either is
     * empty, the holistic/subsequence score is 0.0 and the
     * subgraph score is 1.0 (trivially matches on empty set).
     */
    public Score evaluate(Workflow reference, Workflow candidate) {
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(candidate, "candidate");
        return new Score(
            holistic(reference, candidate),
            subsequence(reference, candidate),
            subgraph(reference, candidate)
        );
    }

    /** Exact match on the full ordered step list AND the full edge set. */
    public double holistic(Workflow reference, Workflow candidate) {
        if (reference.steps().isEmpty() || candidate.steps().isEmpty()) return 0.0;
        return reference.steps().equals(candidate.steps())
            && reference.edges().equals(candidate.edges()) ? 1.0 : 0.0;
    }

    /**
     * Length of the longest common contiguous subsequence, normalised
     * by the reference length. {@code LCS} (Longest Common Substring)
     * is the right primitive here, not {@code LCS} of edit distance.
     */
    public double subsequence(Workflow reference, Workflow candidate) {
        if (reference.steps().isEmpty() || candidate.steps().isEmpty()) return 0.0;
        int n = reference.steps().size();
        int m = candidate.steps().size();
        int[][] dp = new int[n + 1][m + 1];
        int best = 0;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                if (reference.steps().get(i - 1).id().equals(candidate.steps().get(j - 1).id())) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                    if (dp[i][j] > best) best = dp[i][j];
                }
            }
        }
        return (double) best / n;
    }

    /**
     * Edge-set overlap: |E_ref ∩ E_cand| / |E_ref|. Paper's
     * "subgraph" mode is more forgiving than edit distance — it
     * only cares whether the candidate's edges exist in the
     * reference, not whether they're in the right order.
     */
    public double subgraph(Workflow reference, Workflow candidate) {
        if (reference.edges().isEmpty()) return 1.0;
        Set<Edge> ref = new HashSet<>(reference.edges());
        Set<Edge> cand = new HashSet<>(candidate.edges());
        long matched = cand.stream().filter(ref::contains).count();
        return (double) matched / ref.size();
    }

    /** Convenience: composite score (0..1). */
    public double composite(Workflow reference, Workflow candidate) {
        return evaluate(reference, candidate).composite();
    }
}
