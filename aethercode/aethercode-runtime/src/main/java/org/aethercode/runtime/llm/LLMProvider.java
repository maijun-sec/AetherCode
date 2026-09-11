package org.aethercode.runtime.llm;

import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Provider-agnostic LLM interface.
 *
 * <p>Java-native equivalent of langchain's <code>BaseChatModel</code>:
 * the agent runtime depends on this interface, never on a concrete
 * model. Implementations live in <code>deepagents-partner-*</code>.</p>
 */
public interface LLMProvider {

    /** Stable identifier (e.g. <code>"anthropic:claude-sonnet-4-5"</code>). */
    String modelId();

    /** Human-readable provider name (e.g. <code>"anthropic"</code>). */
    String provider();

    /**
     * Issue a synchronous chat completion.
     *
     * <p>Mirrors langchain's <code>BaseChatModel.invoke</code>.</p>
     */
    LLMResponse invoke(LLMRequest request);

    /**
     * Issue an asynchronous chat completion.
     *
     * <p>Mirror of langchain's <code>BaseChatModel.ainvoke</code>.</p>
     */
    CompletableFuture<LLMResponse> ainvoke(LLMRequest request);

    /**
     * Stream a chat completion, yielding partial {@link LLMResponse}s
     * as the model produces output. The terminal response carries the
     * final {@code AIMessage}; intermediates may carry incremental text.
     */
    Stream<LLMResponse> stream(LLMRequest request);
}
