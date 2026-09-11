package org.aethercode.deepagents.langchain_compat.langchain_anthropic;

import org.aethercode.deepagents.langchain_compat.middleware.AgentMiddleware;
import org.aethercode.deepagents.langchain_compat.middleware.AgentState;
import org.aethercode.deepagents.langchain_compat.middleware.ExtendedModelResponse;
import org.aethercode.deepagents.langchain_compat.middleware.ModelRequest;
import org.aethercode.deepagents.langchain_compat.middleware.ModelResponse;
import org.aethercode.deepagents.langchain_compat.messages.LangChainMessage;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * LangChain-compatible AnthropicPromptCachingMiddleware.
 *
 * <p>Java-native port of
 * {@code langchain_anthropic.middleware.AnthropicPromptCachingMiddleware}.
 * The middleware injects {@code cache_control} markers on the
 * most recent system message and the last few tool definitions
 * so Anthropic's prompt-cache hits on subsequent requests.</p>
 *
 * <p>The Java port records the configuration and exposes the
 * tool cache breakpoint count and ttl; the actual marker
 * injection is the runtime's responsibility when it builds
 * the Anthropic request payload.</p>
 */
public class AnthropicPromptCachingMiddleware
        extends AgentMiddleware<AgentState, Object, ModelResponse> {

    private final int toolCacheBreakpoint;
    private final String ttl;

    public AnthropicPromptCachingMiddleware(int toolCacheBreakpoint, String ttl) {
        this.toolCacheBreakpoint = toolCacheBreakpoint;
        this.ttl = ttl == null ? "5m" : ttl;
    }

    public AnthropicPromptCachingMiddleware() {
        this(2, "5m");
    }

    @Override
    public String name() { return "AnthropicPromptCachingMiddleware"; }

    public int toolCacheBreakpoint() { return toolCacheBreakpoint; }
    public String ttl() { return ttl; }

    @Override
    public ModelResponse wrapModelCall(BiFunction<List<?>, Object, ModelResponse> modelCall,
                                       List<?> messages,
                                       AgentState state,
                                       Object runtime) {
        // Inject cache markers on the most recent system message
        // (caller-side) and on the trailing tool definitions.
        // The Java port forwards to the model call unmodified; the
        // runtime is expected to read cacheBreakpoint + ttl to
        // shape the Anthropic request.
        return modelCall.apply(messages, runtime);
    }
}
