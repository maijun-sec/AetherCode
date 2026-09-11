package org.aethercode.deepagents.middleware;

import java.util.logging.Logger;

/**
 * Stub for the Anthropic provider-specific prompt-caching middleware.
 *
 * <p>Java-native port of
 * {@code langchain_anthropic.middleware.AnthropicPromptCachingMiddleware}
 * (a thin shim that tags the static system prompt with
 * {@code cache_control: {"type": "ephemeral"}} so the Anthropic API
 * reuses the prefix across calls).</p>
 *
 * <p>The Java port does not link against the Anthropic SDK &mdash;
 * we don't have an auth story, and the SDK itself is large. The
 * middleware is a placeholder that downstream consumers can swap
 * with a real implementation by registering one on the
 * {@link PromptCachingProviderRegistry} under the {@code "anthropic"}
 * key.</p>
 *
 * <p>The constructor argument {@code unsupportedModelBehavior} is
 * preserved for parity with the Python port: when the runtime
 * detects a model that does not support prompt caching (e.g.
 * Bedrock Claude via some wrappers), the configured behavior
 * ("ignore" by default) is what the SDK does in response. The
 * Java port records the choice and returns "ignore" for any
 * caller asking.</p>
 */
public class PromptCachingMiddleware implements Middleware {
    /** Behavior when the active model does not support prompt caching. */
    public enum UnsupportedModelBehavior { IGNORE, WARN, RAISE }

    private static final Logger LOGGER = Logger.getLogger(PromptCachingMiddleware.class.getName());

    private final String providerName;
    private final UnsupportedModelBehavior unsupportedModelBehavior;

    public PromptCachingMiddleware(String providerName, UnsupportedModelBehavior unsupportedModelBehavior) {
        this.providerName = providerName;
        this.unsupportedModelBehavior = unsupportedModelBehavior;
    }

    public PromptCachingMiddleware() {
        this("anthropic", UnsupportedModelBehavior.IGNORE);
    }

    @Override
    public String name() {
        return "PromptCachingMiddleware[" + providerName + "]";
    }

    @Override
    public int priority() {
        // Prompt caching middleware runs early so its cache breakpoints
        // are visible to everything downstream.
        return -100;
    }

    public String providerName() {
        return providerName;
    }

    public UnsupportedModelBehavior unsupportedModelBehavior() {
        return unsupportedModelBehavior;
    }

    /**
     * No-op before/after hooks: the actual prompt-cache tagging is the
     * responsibility of a registered provider implementation. The
     * default middleware just logs at FINEST so consumers can see
     * whether it was wired into the chain.
     */
    @Override
    public org.aethercode.core.runtime.AgentState beforeModel(
            org.aethercode.core.runtime.AgentState state, Runtime runtime) {
        LOGGER.finest(() -> "PromptCachingMiddleware[" + providerName
                + "] beforeModel; unsupportedModelBehavior=" + unsupportedModelBehavior);
        return state;
    }
}
