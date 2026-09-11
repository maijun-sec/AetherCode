package org.aethercode.deepagents.middleware;

import java.util.Objects;

/**
 * A pre-compiled subagent spec.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.subagents.CompiledSubAgent}. The
 * record holds the (name, description) plus a pre-compiled runnable
 * the consumer built ahead of time. The Java port uses
 * {@link java.util.concurrent.Callable} for the runnable so the type
 * signature stays concrete; consumers that need a richer graph
 * runtime can wrap their graph in a {@code Callable}.</p>
 */
public record CompiledSubAgent(String name, String description,
                               java.util.concurrent.Callable<SubAgentResult> runnable) {

    public CompiledSubAgent {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must be non-blank");
        }
        Objects.requireNonNull(runnable, "runnable");
    }

    public SubAgentResult invoke() throws Exception {
        return runnable.call();
    }
}
