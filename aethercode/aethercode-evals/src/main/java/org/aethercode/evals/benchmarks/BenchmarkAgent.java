/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.evals.benchmarks;

/**
 * Functional interface for one benchmark agent: takes a
 * {@link BenchmarkTask}, returns the agent's output as a string.
 * <p>
 * Implementations:
 * <ul>
 *   <li>{@link BenchmarkLlmAgent} — backed by a real or mock
 *       {@link org.aethercode.core.llm.ChatClient}; this is the
 *       "real" path.</li>
 *   <li>Stub agents in tests — for harness validation only.</li>
 * </ul>
 */
@FunctionalInterface
public interface BenchmarkAgent {
    String run(BenchmarkTask task);
}
