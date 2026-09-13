package org.aethercode.orchestration.planner;

import java.util.Objects;

/**
 * Small-vs-Large agent router (arXiv:2601.11327, ICLR 2026 Workshop MALGAI).
 *
 * <p>The paper asks: when is "many small agents collaborating"
 * better than "one big LLM"? Key findings:
 * <ul>
 *   <li>Small-agent collaboration WINS when the task is decomposable
 *       into independent subtasks and each subtask is small enough
 *       that a small model can solve it accurately.</li>
 *   <li>One big LLM WINS when the task requires long context,
 *       cross-step reasoning, or when the routing overhead of
 *       splitting is higher than the small-model speedup.</li>
 *   <li>The crossover is sharp: the paper observes a ~3× cost
 *       ratio where neither dominates.</li>
 * </ul>
 *
 * <p>This class is the AetherCode Tier-3 implementation. Given
 * a {@link TaskSignal}, it returns a routing decision:
 * {@code SMALL_MULTI} (dispatch to N small agents), {@code BIG_SINGLE}
 * (dispatch to one big LLM), or {@code HYBRID} (a small model drafts,
 * a big model judges).
 */
public final class ModelSizeRouter {

    /** Task features used to decide which architecture to use. */
    public record TaskSignal(
        boolean decomposable,    // can be split into independent subtasks
        boolean longContext,     // needs >32K context
        boolean crossStepReason, // requires info from step N to solve step N+1
        int numSubtasks,         // estimated number of independent subtasks
        double budgetRatio       // (cost of N small) / (cost of 1 big)
    ) {
        public TaskSignal {
            if (numSubtasks < 1) numSubtasks = 1;
        }
    }
    /** Routing decision. */
    public enum Decision { SMALL_MULTI, BIG_SINGLE, HYBRID }
    /** A decision + the rationale. */
    public record Routing(Decision decision, String rationale) {
        public Routing { Objects.requireNonNull(decision, "decision"); rationale = rationale == null ? "" : rationale; }
    }

    /** Tunable crossover parameters. */
    public record Thresholds(
        int maxSubtasksForSmall,    // numSubtasks <= this AND decomposable => SMALL_MULTI
        double maxBudgetRatio       // (cost N small / cost 1 big) <= this => SMALL_MULTI
    ) {
        public Thresholds { if (maxSubtasksForSmall < 1) maxSubtasksForSmall = 1; }
    }

    private final Thresholds thresholds;

    public ModelSizeRouter() { this(new Thresholds(5, 0.5)); }
    public ModelSizeRouter(Thresholds thresholds) {
        this.thresholds = Objects.requireNonNull(thresholds, "thresholds");
    }

    /** Decide which architecture wins for the given task. */
    public Routing route(TaskSignal signal) {
        Objects.requireNonNull(signal, "signal");
        if (signal.longContext() || signal.crossStepReason()) {
            return new Routing(Decision.BIG_SINGLE,
                "task requires long context or cross-step reasoning; "
                + "splitting would lose shared context");
        }
        if (signal.decomposable()
            && signal.numSubtasks() <= thresholds.maxSubtasksForSmall
            && signal.budgetRatio() <= thresholds.maxBudgetRatio) {
            return new Routing(Decision.SMALL_MULTI,
                "task decomposes into " + signal.numSubtasks()
                + " independent subtasks; small-multi "
                + "is " + (1.0 / Math.max(0.01, signal.budgetRatio()))
                + "× cheaper per subtask");
        }
        if (signal.decomposable() && signal.numSubtasks() > thresholds.maxSubtasksForSmall) {
            return new Routing(Decision.HYBRID,
                "too many subtasks for small-multi to coordinate "
                + "(> " + thresholds.maxSubtasksForSmall + "); use big model as orchestrator");
        }
        return new Routing(Decision.BIG_SINGLE,
            "task is not decomposable; single big LLM is the right fit");
    }
}
