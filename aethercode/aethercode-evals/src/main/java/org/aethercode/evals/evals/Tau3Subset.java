package org.aethercode.evals.evals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Curated tau3-bench subset for probing deep-agent conversation behavior.
 *
 * <p>30 tasks (drawn from telecom + banking_knowledge) stratified by
 * difficulty for a behavior spread, not leaderboard parity. Tiers are the
 * <em>measured</em> pass rate of {@code anthropic:claude-opus-4-8} over 3
 * rollouts per task at full agent timeout (langsmith sandbox,
 * tau3-runtime user simulator on gpt-5.2):</p>
 *
 * <ul>
 *   <li>{@code easy} = solved 3/3 rollouts (reliably passes)</li>
 *   <li>{@code medium} = solved 1-2/3 rollouts (passes intermittently)</li>
 *   <li>{@code hard} = solved 0/3 rollouts (not solved)</li>
 * </ul>
 *
 * <p>Opus finds most of this set hard, which is expected/acceptable
 * headroom for a difficulty probe. Living selection: re-run and re-tier
 * here (updating each {@code justification} with the new pass rate) as
 * the reference model or task set changes.
 * {@link #includeTasks()} is derived from {@link #tasks()} -- CI reads it
 * via {@code Tau3Subset.includeTasks()}.</p>
 *
 * <p>Java 21 port of {@code deepagents_evals.tau3_subset}.</p>
 */
public final class Tau3Subset {

    /** Source dataset name (sierra-research/tau3-bench). */
    public static final String DATASET = "sierra-research/tau3-bench";

    /** Difficulty tier for a single subset task. */
    public enum Tier {
        EASY("easy"),
        MEDIUM("medium"),
        HARD("hard");

        private final String label;

        Tier(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static Tier fromLabel(String label) {
            for (Tier t : values()) {
                if (t.label.equals(label)) {
                    return t;
                }
            }
            throw new IllegalArgumentException("unknown tier: " + label);
        }
    }

    /** One curated task: its local id, difficulty tier, and why it sits there. */
    public record SubsetTask(String taskId, Tier tier, String justification) {
        public SubsetTask {
            // Reject malformed rows at construction time. The module is built
            // entirely of module-level SubsetTask(...) literals, so import
            // doubles as a self-test. `tier` is additionally constrained
            // statically by the Tier enum.
            if (taskId == null || !taskId.startsWith("tau3-")) {
                throw new IllegalArgumentException("task_id must start with 'tau3-': " + taskId);
            }
            if (justification == null || justification.strip().isEmpty()) {
                throw new IllegalArgumentException(
                        "justification must be non-empty for " + taskId);
            }
        }
    }

    private static final List<SubsetTask> TASKS = List.of(
            // --- EASY ---
            new SubsetTask(
                    "tau3-banking_knowledge-task-050",
                    Tier.EASY,
                    "Banking knowledge-retrieval; Opus 4.8 solved 3/3 rollouts (full timeout) -- reliably passes."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-093",
                    Tier.EASY,
                    "Banking knowledge-retrieval; Opus 4.8 solved 3/3 rollouts (full timeout) -- reliably passes."),
            // --- MEDIUM ---
            new SubsetTask(
                    "tau3-telecom-service-issue-break-apn-settings-lock-sim-card-pin-overdue-bill-suspension-unseat-sim-card-persona-easy",
                    Tier.MEDIUM,
                    "Telecom; Opus 4.8 solved 2/3 rollouts -- passes intermittently."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-043",
                    Tier.MEDIUM,
                    "Banking knowledge-retrieval; Opus 4.8 solved 1/3 rollouts -- passes intermittently."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-056",
                    Tier.MEDIUM,
                    "Banking knowledge-retrieval; Opus 4.8 solved 1/3 rollouts -- passes intermittently."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-072",
                    Tier.MEDIUM,
                    "Banking knowledge-retrieval; Opus 4.8 solved 1/3 rollouts -- passes intermittently."),
            new SubsetTask(
                    "tau3-telecom-service-issue-airplane-mode-on-break-apn-settings-contract-end-suspension-unseat-sim-card-persona-easy",
                    Tier.MEDIUM,
                    "Telecom; Opus 4.8 solved 1/3 rollouts -- passes intermittently."),
            new SubsetTask(
                    "tau3-telecom-service-issue-airplane-mode-on-break-apn-settings-lock-sim-card-pin-overdue-bill-suspension-unseat-sim-card-persona-none",
                    Tier.MEDIUM,
                    "Telecom; Opus 4.8 solved 1/3 rollouts -- passes intermittently."),
            new SubsetTask(
                    "tau3-telecom-service-issue-airplane-mode-on-lock-sim-card-pin-unseat-sim-card-persona-hard",
                    Tier.MEDIUM,
                    "Telecom; Opus 4.8 solved 1/3 rollouts -- passes intermittently."),
            // --- HARD ---
            new SubsetTask(
                    "tau3-banking_knowledge-task-018",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-026",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-029",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-039",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-040",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-048",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-052",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-061",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-064",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-070",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-071",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-073",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-077",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-079",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-080",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-081",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-091",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-096",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-banking_knowledge-task-097",
                    Tier.HARD,
                    "Banking knowledge-retrieval; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-telecom-service-issue-airplane-mode-on-break-apn-settings-lock-sim-card-pin-persona-none",
                    Tier.HARD,
                    "Telecom; Opus 4.8 solved 0/3 rollouts -- not solved."),
            new SubsetTask(
                    "tau3-telecom-service-issue-airplane-mode-on-lock-sim-card-pin-overdue-bill-suspension-unseat-sim-card-persona-easy",
                    Tier.HARD,
                    "Telecom; Opus 4.8 solved 0/3 rollouts -- not solved."));

    static {
        // A duplicate task_id would double-weight a task and skew the
        // difficulty distribution while silently passing every len()==30
        // check; reject it at import (a copy-paste slip during re-tiering
        // is the likely cause).
        Set<String> seen = new HashSet<>();
        for (SubsetTask t : TASKS) {
            if (!seen.add(t.taskId())) {
                throw new IllegalStateException("duplicate task_id in TASKS");
            }
        }
    }

    private Tau3Subset() {}

    /** Read-only view of the curated task list, in module order. */
    public static List<SubsetTask> tasks() {
        return TASKS;
    }

    /**
     * Space-separated Harbor {@code include_tasks} value for the workflow's
     * dataset.
     */
    public static String includeTasks() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < TASKS.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(DATASET).append("__").append(TASKS.get(i).taskId());
        }
        return sb.toString();
    }

    /** Tasks of a given tier, in module order. */
    public static List<SubsetTask> tasksByTier(Tier tier) {
        List<SubsetTask> out = new ArrayList<>();
        for (SubsetTask t : TASKS) {
            if (t.tier() == tier) {
                out.add(t);
            }
        }
        return out;
    }
}
