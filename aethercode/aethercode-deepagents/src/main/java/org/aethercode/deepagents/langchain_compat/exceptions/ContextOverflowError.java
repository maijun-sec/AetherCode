package org.aethercode.deepagents.langchain_compat.exceptions;

/**
 * LangChain-compatible {@code ContextOverflowError}.
 *
 * <p>Java-native port of
 * {@code langchain_core.exceptions.ContextOverflowError}. Thrown
 * when a chat-model call would exceed the model's context
 * window. The runtime is expected to catch this and route to
 * the summarization middleware for compaction.</p>
 */
public class ContextOverflowError extends RuntimeException {

    private final int requestedTokens;
    private final int maxTokens;

    public ContextOverflowError(String message, int requestedTokens, int maxTokens) {
        super(message);
        this.requestedTokens = requestedTokens;
        this.maxTokens = maxTokens;
    }

    public ContextOverflowError(String message) {
        this(message, -1, -1);
    }

    public int requestedTokens() { return requestedTokens; }
    public int maxTokens() { return maxTokens; }

    public static ContextOverflowError fromTokenCounts(int requested, int max) {
        return new ContextOverflowError(
                "Context overflow: requested " + requested + " tokens, max is " + max,
                requested, max);
    }
}
