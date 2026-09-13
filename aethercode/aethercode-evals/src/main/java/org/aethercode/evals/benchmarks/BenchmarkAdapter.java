package org.aethercode.evals.benchmarks;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Adapter that turns a benchmark dataset (HumanEval, MMLU, SWE-bench, ...)
 * into a stream of {@link BenchmarkTask} instances.
 * <p>
 * Adapters are the bridge between (a) raw downloaded data (Arrow / JSONL
 * on disk) and (b) AetherCode's test/eval pipeline. Each adapter
 * normalizes the dataset's native schema into {@link BenchmarkTask}.
 *
 * <h2>Lifecycle</h2>
 * <pre>{@code
 *   BenchmarkAdapter adapter = new HumanEvalAdapter("/path/to/humaneval");
 *   for (BenchmarkTask task : adapter.loadAll()) {
 *       String agentOutput = runAgent(task.prompt());
 *       boolean pass = adapter.grade(task, agentOutput);
 *   }
 * }</pre>
 *
 * <h2>Built-in adapters</h2>
 * <ul>
 *   <li>{@link HumanEvalAdapter} — 164 free-form Python coding tasks</li>
 *   <li>{@link MMLUAdapter} — multiple-choice questions, 57 subjects</li>
 *   <li>{@link SweBenchAdapter} — 500 SWE-bench Verified issues</li>
 * </ul>
 */
public interface BenchmarkAdapter {

    /** The benchmark's human-readable name (e.g. "HumanEval"). */
    String name();

    /** The split this adapter exposes (e.g. "test"). */
    String split();

    /** Total number of tasks in this adapter's loaded set. */
    int size();

    /** Load all tasks. May be expensive for large datasets; prefer iterator. */
    List<BenchmarkTask> loadAll();

    /** Stream tasks one at a time. */
    Iterator<BenchmarkTask> iterator();

    /**
     * Grade the agent's output against the task's expected output.
     * Returns true if the agent's answer is correct.
     * <p>
     * Implementations should be deterministic and side-effect-free.
     * The default implementation is exact string match after
     * normalization; subclasses may override for multiple-choice
     * (MMLU) or code execution (HumanEval).
     */
    default boolean grade(BenchmarkTask task, String agentOutput) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(agentOutput, "agentOutput");
        if (task.choices().isEmpty()) {
            return normalize(agentOutput).equals(normalize(task.expectedOutput()));
        }
        // multiple-choice: agent output should be a letter (A/B/C/D),
        // the choice index (0-3), or the full choice text.
        String trimmed = agentOutput.trim();
        if (trimmed.length() == 1 && Character.isLetter(trimmed.charAt(0))) {
            int idx = Character.toUpperCase(trimmed.charAt(0)) - 'A';
            return idx >= 0 && idx < task.choices().size()
                && String.valueOf(idx).equals(task.expectedOutput());
        }
        if (trimmed.length() == 1 && Character.isDigit(trimmed.charAt(0))) {
            return trimmed.equals(task.expectedOutput());
        }
        // full text match: find which choice index the text corresponds to
        for (int i = 0; i < task.choices().size(); i++) {
            if (trimmed.equalsIgnoreCase(task.choices().get(i).trim())) {
                return String.valueOf(i).equals(task.expectedOutput());
            }
        }
        // last resort: direct string match
        return trimmed.equalsIgnoreCase(task.expectedOutput());
    }

    /** Default normalization: trim, strip, lowercase. */
    static String normalize(String s) {
        if (s == null) return "";
        return s.trim().strip().toLowerCase();
    }
}
