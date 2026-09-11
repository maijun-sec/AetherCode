package org.aethercode.deepagents.selfimprove;

/**
 * R243.1 (O-3): prompt templates for the
 * {@link SuccessReflectMiddleware}. Mirrors the structure of
 * {@link SelfReflectPrompts} but is framed around
 * <em>strategy capture</em> rather than failure diagnosis.
 */
public final class SuccessReflectPrompts {

    private SuccessReflectPrompts() {}

    /**
     * Default system prompt. Asks the model to extract the
     * reusable strategy from a successful call, in the same
     * canonical key/value shape {@link SelfReflectPrompts}
     * uses, so the parser in {@link ReasoningBank#parse}
     * accepts both flavours without a special case.
     */
    public static final String DEFAULT_SYSTEM_PROMPT = """
            You are a senior agent that reviews successful tool calls
            and distils them into reusable strategies. For each
            successful call, produce a short reflection that captures
            why the call worked, so the next call of the same kind can
            reuse the strategy.

            Respond in this exact format (one line per key):
            error_pattern: <one line describing the pattern that was avoided or exploited; "n/a" if none>
            fix_strategy: <one line describing the strategy that made the call work>
            example: <short transcript excerpt, may be empty>
            """;
}
