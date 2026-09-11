package org.aethercode.sdk;

import java.util.List;
import java.util.Set;

/**
 * classify a list of plan steps as either {@link #TRIVIAL}
 * (safe to auto-approve and execute) or {@link #NEEDS_APPROVAL}
 * (require user confirmation).
 *
 * <p>"Trivial" is a conservative definition: the plan must have
 * at most {@link #DEFAULT_MAX_STEPS} steps AND none of the
 * steps may involve a destructive tool. The destructive-tool
 * set is configurable via the constructor; the default includes
 * shell-execution and file-mutation tools that could lose work
 * or change system state.
 *
 * <p>The classifier takes a {@link List} of {@link Step} (a small
 * SDK-level record) instead of the TUI's {@code StructuredPlan},
 * so it doesn't create a backwards dependency from the SDK
 * onto the TUI module. The TUI's {@code PlanPanel.approve}
 * adapts its plan to this shape.
 */
public final class PlanClassifier {

    /** verdict. */
    public enum Verdict { TRIVIAL, NEEDS_APPROVAL }

    /** default threshold — a plan with more steps than this
     *  always needs approval. Three is small enough to keep the
     *  blast radius contained, large enough to cover common
     *  "read X, write Y, run Z" workflows. */
    public static final int DEFAULT_MAX_STEPS = 3;

    /** default destructive-tool set. Tool names are matched
     *  case-insensitively as substrings of the step title. The
     *  names follow the convention used by {@code StandardTools}
     *  in {@code aethercode-tools}. */
    public static final Set<String> DEFAULT_DESTRUCTIVE_TOOLS = Set.of(
            "bash",      // shell execution
            "shell",
            "file_write",
            "file_edit",
            "file_delete",
            "web_fetch",  // network read
            "process_kill"
    );

    /** minimal plan-step record. Mirrors
     *  {@code StructuredPlan.Step}'s shape but lives in the SDK
     *  so the classifier has no TUI dependency. */
    public record Step(String title) {}

    private final int maxSteps;
    private final Set<String> destructiveTools;

    public PlanClassifier() {
        this(DEFAULT_MAX_STEPS, DEFAULT_DESTRUCTIVE_TOOLS);
    }

    public PlanClassifier(int maxSteps, Set<String> destructiveTools) {
        this.maxSteps = maxSteps;
        this.destructiveTools = destructiveTools == null
                ? Set.of()
                : Set.copyOf(destructiveTools);
    }

    /** classify a plan. Returns {@link Verdict#TRIVIAL} when
     *  the plan is safe to auto-execute, or
     *  {@link Verdict#NEEDS_APPROVAL} when the user must confirm. */
    public Verdict classify(List<Step> steps) {
        if (steps == null || steps.isEmpty()) return Verdict.NEEDS_APPROVAL;
        if (steps.size() > maxSteps) return Verdict.NEEDS_APPROVAL;
        for (var step : steps) {
            String title = step.title() == null ? "" : step.title().toLowerCase();
            for (String tool : destructiveTools) {
                if (title.contains(tool.toLowerCase())) {
                    return Verdict.NEEDS_APPROVAL;
                }
            }
        }
        return Verdict.TRIVIAL;
    }

    /** convenience for callers that just want a boolean. */
    public boolean isTrivial(List<Step> plan) {
        return classify(plan) == Verdict.TRIVIAL;
    }

    public int maxSteps() { return maxSteps; }
    public Set<String> destructiveTools() { return destructiveTools; }

    /** build a structured rationale explaining why a plan
     *  was classified one way or the other. Useful for the TUI's
     *  approval prompt. */
    public String rationale(List<Step> steps) {
        if (steps == null || steps.isEmpty()) return "no approved steps";
        if (steps.size() > maxSteps) {
            return steps.size() + " steps > max " + maxSteps;
        }
        for (var step : steps) {
            String title = step.title() == null ? "" : step.title().toLowerCase();
            for (String tool : destructiveTools) {
                if (title.contains(tool.toLowerCase())) {
                    return "step \"" + step.title() + "\" mentions destructive tool '" + tool + "'";
                }
            }
        }
        return "all " + steps.size() + " step(s) are read-only / safe";
    }
}
