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

package org.aethercode.orchestration.multiagent;

import java.util.List;

/**
 * Functional interface for one agent in a multi-agent ensemble.
 *
 * <p>An agent takes a {@code prompt} (the shared task) and an optional
 * list of {@code peers} (the other agents' previous outputs, in the
 * case of debate or critique) and returns its output.</p>
 *
 * <p>For vote strategies, the {@code peers} list is empty. For debate
 * strategies, it contains the previous-round outputs in the same order
 * the agents were registered. For critique strategies, the
 * orchestrator runs the critics in a second pass with the proposals
 * as part of the prompt.</p>
 */
@FunctionalInterface
public interface AgentFn<T> {

    /**
     * Produce this agent's output for {@code prompt}, optionally
     * informed by {@code peers} (previous-round outputs from the
     * other agents in the ensemble).
     *
     * <p>Implementations are expected to be pure functions of the
     * inputs (no hidden state across calls). Tests use deterministic
     * stubs; production wires real agents via
     * {@code aethercode-a2a} or {@code aethercode-tools/task/AgentTool}.</p>
     */
    T respond(String prompt, List<T> peers);

    /** Stable name for audit logs ("claude-sonnet-4-6", "gpt-5.4", etc.). */
    default String name() { return getClass().getSimpleName(); }
}
