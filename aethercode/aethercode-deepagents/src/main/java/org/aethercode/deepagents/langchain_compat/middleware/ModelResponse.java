package org.aethercode.deepagents.langchain_compat.middleware;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * LangChain-compatible model response type.
 *
 * <p>Java-native port of
 * {@code langchain.agents.middleware.types.ModelResponse}. Carries
 * the data returned by a chat model call: the AI message plus
 * any tool calls the model made.</p>
 */
public class ModelResponse {
    private final Object aiMessage;
    private final List<Map<String, Object>> toolCalls;

    public ModelResponse(Object aiMessage, List<Map<String, Object>> toolCalls) {
        this.aiMessage = aiMessage;
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public ModelResponse(Object aiMessage) {
        this(aiMessage, List.of());
    }

    public Object aiMessage() { return aiMessage; }
    public List<Map<String, Object>> toolCalls() { return toolCalls; }

    public static ModelResponse of(Object aiMessage) {
        Objects.requireNonNull(aiMessage, "aiMessage");
        return new ModelResponse(aiMessage);
    }

    public static ModelResponse of(Object aiMessage, List<Map<String, Object>> toolCalls) {
        Objects.requireNonNull(aiMessage, "aiMessage");
        return new ModelResponse(aiMessage, toolCalls);
    }
}
