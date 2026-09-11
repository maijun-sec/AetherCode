package org.aethercode.deepagents.langchain_compat.middleware.summarization;

import org.aethercode.deepagents.langchain_compat.middleware.AgentMiddleware;
import org.aethercode.deepagents.langchain_compat.middleware.AgentState;
import org.aethercode.deepagents.langchain_compat.middleware.ExtendedModelResponse;
import org.aethercode.deepagents.langchain_compat.middleware.ModelRequest;
import org.aethercode.deepagents.langchain_compat.middleware.ModelResponse;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * LangChain-compatible summarization middleware.
 *
 * <p>Java-native port of
 * {@code langchain.agents.middleware.summarization.SummarizationMiddleware}.
 * The base class mirrors the Python port's hook surface: it
 * can either auto-compact on a configured trigger (via
 * {@code beforeModel}) or summarize on demand via
 * {@code wrapModelCall} when the model emits a synthetic
 * {@code "compact"} tool call.</p>
 *
 * <p>The full implementation is deferred to the project-local
 * {@code org.aethercode.deepagents.middleware.SummarizationMiddleware}; this
 * class is a thin alias that extends the LangChain-compatible
 * {@link AgentMiddleware} base class so middleware ported from
 * LangChain Python can be dropped in directly.</p>
 */
public class SummarizationMiddleware
        extends AgentMiddleware<AgentState, Object, ModelResponse> {

    private final String model;
    private final Object trigger;
    private final Object keep;
    private final String summaryPrompt;

    public SummarizationMiddleware(String model,
                                     Object trigger,
                                     Object keep,
                                     String summaryPrompt) {
        this.model = model;
        this.trigger = trigger == null ? "messages:20" : trigger;
        this.keep = keep == null ? "messages:20" : keep;
        this.summaryPrompt = summaryPrompt;
    }

    public SummarizationMiddleware() {
        this(null, null, null, null);
    }

    public SummarizationMiddleware(String model) {
        this(model, null, null, null);
    }

    @Override
    public String name() { return "SummarizationMiddleware"; }

    public String model() { return model; }
    public Object trigger() { return trigger; }
    public Object keep() { return keep; }
    public String summaryPrompt() { return summaryPrompt; }

    /**
     * Extract the inner {@link ModelResponse} from an
     * {@link ExtendedModelResponse}, when the runtime supplies
     * one. Used by middleware subclasses that need to introspect
     * the call result.
     */
    public static ModelResponse unwrap(ModelResponse response) {
        Objects.requireNonNull(response, "response");
        // ExtendedModelResponse is-a ModelResponse; no conversion needed.
        return response;
    }

    @Override
    public ModelResponse wrapModelCall(BiFunction<List<?>, Object, ModelResponse> modelCall,
                                       List<?> messages,
                                       AgentState state,
                                       Object runtime) {
        // Passthrough: the runtime is expected to handle compaction
        // via beforeModel or a synthetic compact tool call.
        return modelCall.apply(messages, runtime);
    }

    /** Build the {@link ModelRequest} for the inner model call. */
    public static ModelRequest modelRequest(List<?> messages, AgentState state) {
        return ModelRequest.builder()
                .messages(messages)
                .state(state)
                .build();
    }
}
