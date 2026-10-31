/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
