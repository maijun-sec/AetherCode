package org.aethercode.deepagents.langchain_compat.middleware;

import java.util.List;
import java.util.Set;

/**
 * LangChain-compatible ToolRetryMiddleware.
 *
 * <p>Java-native port of
 * {@code langchain.agents.middleware.ToolRetryMiddleware}. Retries
 * failed tool calls with configurable backoff. The Java port
 * holds the configuration in immutable fields; the runtime is
 * expected to honor the policy when invoking the tool.</p>
 */
public class ToolRetryMiddleware extends AgentMiddleware<AgentState, Object, ModelResponse> {

    private final int maxRetries;
    private final Set<String> tools;
    private final String onFailure;
    private final double initialDelay;
    private final double backoffFactor;
    private final double maxDelay;
    private final boolean jitter;

    public ToolRetryMiddleware(int maxRetries,
                                Set<String> tools,
                                String onFailure,
                                double initialDelay,
                                double backoffFactor,
                                double maxDelay,
                                boolean jitter) {
        this.maxRetries = maxRetries;
        this.tools = tools == null ? Set.of() : Set.copyOf(tools);
        this.onFailure = onFailure == null ? "raise" : onFailure;
        this.initialDelay = initialDelay;
        this.backoffFactor = backoffFactor;
        this.maxDelay = maxDelay;
        this.jitter = jitter;
    }

    public ToolRetryMiddleware() {
        this(2, Set.of(), "raise", 1.0, 2.0, 60.0, false);
    }

    @Override
    public String name() { return "ToolRetryMiddleware"; }

    public int maxRetries() { return maxRetries; }
    public Set<String> tools() { return tools; }
    public String onFailure() { return onFailure; }
    public double initialDelay() { return initialDelay; }
    public double backoffFactor() { return backoffFactor; }
    public double maxDelay() { return maxDelay; }
    public boolean jitter() { return jitter; }

    /** Whether the given tool is in the configured retry set. */
    public boolean appliesTo(String toolName) {
        return tools.isEmpty() || tools.contains(toolName);
    }

    /**
     * Compute the next backoff delay in seconds.
     *
     * @param attempt the attempt index (0 for the first retry)
     * @return the delay in seconds, clamped to {@link #maxDelay()}
     */
    public double nextDelay(int attempt) {
        double delay = initialDelay * Math.pow(backoffFactor, attempt);
        if (delay > maxDelay) delay = maxDelay;
        if (jitter) {
            delay = delay * (0.5 + Math.random() * 0.5);
        }
        return delay;
    }
}
