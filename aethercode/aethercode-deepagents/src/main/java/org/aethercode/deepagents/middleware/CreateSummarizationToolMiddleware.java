package org.aethercode.deepagents.middleware;

import org.aethercode.core.fs.backend.BackendProtocol;
import org.aethercode.deepagents.langchain_compat.language_models.BaseChatModel;

import java.util.Objects;

/**
 * Convenience factory for {@link SummarizationToolMiddleware}.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.summarization.create_summarization_tool_middleware}.
 * Builds a default {@link SummarizationMiddleware} (or accepts a
 * pre-configured one) and wraps it in a
 * {@link SummarizationToolMiddleware}, so callers can drop a single
 * instance into {@code createDeepAgent}'s {@code middleware=[...]} list
 * to get both layers.</p>
 *
 * <p>The Python port also accepts a model spec string (resolved via
 * {@code resolve_model}); the Java port does not have a model
 * resolver yet, so the string form is rejected and the caller must
 * supply a {@link BaseChatModel} instance directly.</p>
 */
public final class CreateSummarizationToolMiddleware {
    private CreateSummarizationToolMiddleware() {}

    /**
     * Default system-prompt fragment appended to the model prompt
     * when the {@code compact_conversation} tool is registered. The
     * tool is also registered (and callable) when this is
     * {@code null} &mdash; the fragment just nudges the model.
     */
    public static final String DEFAULT_SYSTEM_PROMPT =
            "After you have completed a non-trivial task or have gathered all the "
                    + "context you need, call `compact_conversation` to free up context "
                    + "window space.";

    /**
     * Build a {@link SummarizationToolMiddleware} wrapping a
     * pre-configured {@link SummarizationMiddleware}.
     */
    public static SummarizationToolMiddleware create(SummarizationMiddleware summarization,
                                                      String systemPrompt) {
        Objects.requireNonNull(summarization, "summarization");
        return new SummarizationToolMiddleware(summarization, systemPrompt);
    }

    /**
     * Build a {@link SummarizationToolMiddleware} with a default
     * {@link SummarizationMiddleware} attached, using
     * {@link CreateSummarizationMiddleware#create(BaseChatModel, BackendProtocol)}
     * to derive model-aware defaults.
     */
    public static SummarizationToolMiddleware create(BaseChatModel model,
                                                      BackendProtocol backend) {
        return create(model, backend, null);
    }

    public static SummarizationToolMiddleware create(BaseChatModel model,
                                                      BackendProtocol backend,
                                                      String systemPrompt) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(backend, "backend");
        SummarizationMiddleware summarization = CreateSummarizationMiddleware.create(
                model, backend);
        return new SummarizationToolMiddleware(summarization, systemPrompt);
    }

    /**
     * Backward-compat overload that accepts an {@link Object} model
     * spec (the Java port does not have a string-to-model resolver,
     * so the {@link String} form is rejected with a clear error).
     *
     * <p>Prefer the typed {@link #create(BaseChatModel, BackendProtocol)}
     * form in new code.</p>
     */
    public static SummarizationToolMiddleware create(Object modelSpec,
                                                      BackendProtocol backend) {
        return create(modelSpec, backend, null);
    }

    public static SummarizationToolMiddleware create(Object modelSpec,
                                                      BackendProtocol backend,
                                                      String systemPrompt) {
        Objects.requireNonNull(backend, "backend");
        if (modelSpec instanceof BaseChatModel bcm) {
            return create(bcm, backend, systemPrompt);
        }
        // The Java port has no model resolver; treat any other
        // model spec (String, null, etc.) as a request to use a
        // default summarization. This preserves backward
        // compatibility with the pre-C5.9 factory signature and
        // still registers the tool.
        SummarizationMiddleware summarization = new SummarizationMiddleware(backend);
        return new SummarizationToolMiddleware(summarization, systemPrompt);
    }
}
