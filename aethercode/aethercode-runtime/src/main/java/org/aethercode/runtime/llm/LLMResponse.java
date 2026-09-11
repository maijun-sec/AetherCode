package org.aethercode.runtime.llm;

import org.aethercode.runtime.message.AIMessage;
import org.aethercode.runtime.message.Usage;

import java.util.Optional;

/**
 * Result of a single LLM call.
 *
 * <p>Mirrors the langchain <code>BaseChatModel.invoke</code> return:
 * an {@link AIMessage} plus optional token {@link Usage} and an
 * optional structured-output payload.</p>
 */
public record LLMResponse(
        AIMessage message,
        Optional<Usage> usage,
        Optional<String> modelName,
        Optional<Object> structuredOutput
) {
    public LLMResponse {
        if (message == null) {
            throw new IllegalArgumentException("LLMResponse.message is required");
        }
    }

    public static LLMResponse of(AIMessage message) {
        return new LLMResponse(message, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public static LLMResponse of(AIMessage message, Usage usage) {
        return new LLMResponse(message, Optional.ofNullable(usage), Optional.empty(), Optional.empty());
    }

    public static LLMResponse structured(AIMessage message, Object structuredOutput) {
        return new LLMResponse(
                message,
                Optional.empty(),
                Optional.empty(),
                Optional.ofNullable(structuredOutput));
    }
}
