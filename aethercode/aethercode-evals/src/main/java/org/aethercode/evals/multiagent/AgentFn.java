package org.aethercode.evals.multiagent;

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
