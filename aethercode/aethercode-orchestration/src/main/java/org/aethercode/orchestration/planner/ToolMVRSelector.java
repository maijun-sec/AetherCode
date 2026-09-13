package org.aethercode.orchestration.planner;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Tool-MVR-style multi-view tool selector (arXiv:2506.04625).
 *
 * <p>Tool-MVR (KDD 2025) selects which tool an agent should call by
 * computing a per-tool score from <b>three views</b>:
 * <ol>
 *   <li><b>Name match</b> — token overlap between the task
 *       description and the tool's name + description.</li>
 *   <li><b>Argument type match</b> — does the tool's input schema
 *       accept the entities mentioned in the task (e.g. an "id"
 *       field when the task says "by id")?</li>
 *   <li><b>Historical success</b> — past observed pass rate of this
 *       tool on similar tasks (caller-supplied; defaults to 0.5).</li>
 * </ol>
 * The final score is the weighted sum. The agent picks the tool
 * with the highest score, and ties are broken by declaration order.
 *
 * <p>This is the AetherCode Tier-3 implementation. Combined with
 * {@link AgentArchitectureSelector} and
 * {@link CapabilitySaturationDetector}, it lets a Tier-3 plan
 * decide not just "which architecture" but "which specific tool"
 * based on a paper-faithful scoring function.
 *
 * <h2>Limitations</h2>
 * This is the deterministic selector, not the LLM reflection loop
 * the paper also describes (that lives in
 * {@code orchestration.verifier.MirrorReflector}). Historical
 * success is the caller's responsibility to maintain.
 */
public final class ToolMVRSelector {

    /** A tool the selector can choose from. */
    public record Tool(String name, String description, List<String> argFields) {
        public Tool {
            Objects.requireNonNull(name, "name");
            description = description == null ? "" : description;
            argFields = argFields == null ? List.of() : List.copyOf(argFields);
        }
    }
    /** Per-tool success rate history. Caller maintains. */
    public interface History {
        /** Success rate for a tool name on similar tasks, [0, 1]. Default 0.5. */
        double successRate(String toolName);
    }
    /** Scoring weights. Paper uses roughly these. */
    public record Weights(double nameMatch, double argTypeMatch, double history) {
        public Weights {
            // normalise so weight vector sums to 1
            double total = nameMatch + argTypeMatch + history;
            if (total <= 0) throw new IllegalArgumentException("at least one weight must be > 0");
            nameMatch /= total;
            argTypeMatch /= total;
            history /= total;
        }
    }
    /** Score for a single candidate tool. */
    public record ToolScore(Tool tool, double score) implements Comparable<ToolScore> {
        @Override public int compareTo(ToolScore o) {
            // higher score first; ties broken by tool name (stable)
            int byScore = Double.compare(o.score, this.score);
            return byScore != 0 ? byScore : tool.name().compareTo(o.tool.name());
        }
    }

    private final Weights weights;

    public ToolMVRSelector() {
        this(new Weights(0.4, 0.4, 0.2));
    }

    public ToolMVRSelector(Weights weights) {
        this.weights = Objects.requireNonNull(weights, "weights");
    }

    /**
     * Score every tool and return them sorted by score (best first).
     * The first entry is the recommended tool.
     */
    public List<ToolScore> rank(String taskDescription, List<Tool> tools, History history) {
        Objects.requireNonNull(taskDescription, "taskDescription");
        Objects.requireNonNull(tools, "tools");
        History h = history == null ? name -> 0.5 : history;
        Set<String> taskTokens = tokenize(taskDescription);
        List<ToolScore> scores = new ArrayList<>(tools.size());
        for (Tool t : tools) {
            double n = nameMatch(taskTokens, t);
            double a = argTypeMatch(taskDescription, t);
            double hist = clamp01(h.successRate(t.name()));
            double s = weights.nameMatch * n
                     + weights.argTypeMatch * a
                     + weights.history * hist;
            scores.add(new ToolScore(t, s));
        }
        scores.sort(Comparator.naturalOrder());
        return scores;
    }

    /** Convenience: best tool, or null if the candidate list is empty. */
    public Tool select(String taskDescription, List<Tool> tools, History history) {
        if (tools == null || tools.isEmpty()) return null;
        return rank(taskDescription, tools, history).get(0).tool();
    }

    /* ---------------- view implementations ---------------- */

    /** Token overlap between task and tool's name + description. */
    private double nameMatch(Set<String> taskTokens, Tool tool) {
        Set<String> toolTokens = tokenize(tool.name() + " " + tool.description());
        if (taskTokens.isEmpty() || toolTokens.isEmpty()) return 0.0;
        Set<String> intersection = new HashSet<>(taskTokens);
        intersection.retainAll(toolTokens);
        // Jaccard
        Set<String> union = new HashSet<>(taskTokens);
        union.addAll(toolTokens);
        return (double) intersection.size() / union.size();
    }

    /** True if the tool's arg fields overlap with entities in the task. */
    private double argTypeMatch(String taskDescription, Tool tool) {
        if (tool.argFields().isEmpty()) return 0.5; // neutral
        long matched = tool.argFields().stream()
            .filter(f -> taskDescription.toLowerCase().contains(f.toLowerCase()))
            .count();
        return (double) matched / tool.argFields().size();
    }

    private static Set<String> tokenize(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String tok : s.toLowerCase().split("[^a-z0-9_]+")) {
            if (tok.length() >= 2) out.add(tok);
        }
        return out;
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.5;
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
