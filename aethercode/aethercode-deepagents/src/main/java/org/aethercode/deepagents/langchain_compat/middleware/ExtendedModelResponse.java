package org.aethercode.deepagents.langchain_compat.middleware;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * LangChain-compatible extended model response.
 *
 * <p>Java-native port of
 * {@code langchain.agents.middleware.types.ExtendedModelResponse}.
 * Like {@link ModelResponse} but additionally carries the
 * underlying model-call function so middleware can replay or
 * amend the call. Used by summarization middleware and other
 * middleware that needs to invoke the model recursively.</p>
 */
public final class ExtendedModelResponse extends ModelResponse {
    private final java.util.function.Function<ModelRequest, ModelResponse> modelCall;

    public ExtendedModelResponse(Object aiMessage,
                                  List<Map<String, Object>> toolCalls,
                                  java.util.function.Function<ModelRequest, ModelResponse> modelCall) {
        super(aiMessage, toolCalls);
        this.modelCall = modelCall;
    }

    public ExtendedModelResponse(Object aiMessage,
                                  java.util.function.Function<ModelRequest, ModelResponse> modelCall) {
        super(aiMessage);
        this.modelCall = modelCall;
    }

    public java.util.function.Function<ModelRequest, ModelResponse> modelCall() {
        return modelCall;
    }

    @Override
    public Object aiMessage() { return super.aiMessage(); }
}
