package org.aethercode.evals.benchmarks;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A single task from an AI agent benchmark dataset.
 * <p>
 * Most benchmarks (HumanEval, MMLU, SWE-bench, GAIA, etc.) can be
 * normalized to this shape:
 * <ul>
 *   <li>{@code id} — unique identifier for the task (string)</li>
 *   <li>{@code prompt} — the input given to the agent (string)</li>
 *   <li>{@code expectedOutput} — the canonical answer (string); may be empty for open-ended tasks</li>
 *   <li>{@code metadata} — additional fields the benchmark provides
 *       (e.g. SWE-bench's "instance_id", HumanEval's "test", MMLU's "choices")</li>
 *   <li>{@code choices} — for multiple-choice tasks (MMLU style); empty for free-form</li>
 * </ul>
 */
public record BenchmarkTask(
    String id,
    String prompt,
    String expectedOutput,
    List<String> choices,
    Map<String, Object> metadata
) {
    public BenchmarkTask {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(expectedOutput, "expectedOutput");
        choices = choices == null ? List.of() : List.copyOf(choices);
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Convenience: a multiple-choice task (MMLU style). */
    public static BenchmarkTask multipleChoice(
        String id, String prompt, int correctIndex, List<String> choices,
        Map<String, Object> metadata
    ) {
        return new BenchmarkTask(id, prompt, String.valueOf(correctIndex), choices, metadata);
    }

    /** Convenience: a free-form task (HumanEval style). */
    public static BenchmarkTask freeForm(
        String id, String prompt, String expected, Map<String, Object> metadata
    ) {
        return new BenchmarkTask(id, prompt, expected, List.of(), metadata);
    }
}
