package org.aethercode.deepagents.middleware;

import org.aethercode.core.middleware.ContextSize;
import org.aethercode.core.middleware.SummarizationPrompts;
import org.aethercode.core.middleware.SummarizationTruncateArgs;

import org.aethercode.core.fs.backend.BackendProtocol;
import org.aethercode.deepagents.langchain_compat.language_models.BaseChatModel;
import org.aethercode.core.runtime.Message;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Convenience factory for {@link SummarizationMiddleware} with
 * model-aware defaults.
 *
 * <p>Java-native port of
 * {@code deepagents.middleware.summarization.create_summarization_middleware}.
 * Mirrors the Python factory's behavior: it inspects the model's
 * profile to pick fraction-based thresholds when the model exposes
 * {@code max_input_tokens}, otherwise it falls back to fixed
 * token/message counts.</p>
 *
 * <p>The returned middleware composes with
 * {@link SummarizationToolMiddleware} so the agent (or a
 * human-in-the-loop approval flow) can trigger compaction on demand.
 * For a tool-only setup, see
 * {@link CreateSummarizationToolMiddleware}.</p>
 */
public final class CreateSummarizationMiddleware {
    private CreateSummarizationMiddleware() {}

    /** Default fraction trigger when a model profile is available. */
    public static final double DEFAULT_PROFILE_TRIGGER_FRACTION = 0.85;
    /** Default fraction keep when a model profile is available. */
    public static final double DEFAULT_PROFILE_KEEP_FRACTION = 0.10;
    /** Fallback fixed-token trigger when no profile is available. */
    public static final int DEFAULT_FALLBACK_TRIGGER_TOKENS = 170_000;
    /** Fallback fixed-message keep when no profile is available. */
    public static final int DEFAULT_FALLBACK_KEEP_MESSAGES = 6;
    /** Fallback fixed-message truncate-args trigger/keep. */
    public static final int DEFAULT_FALLBACK_TRUNCATE_ARGS_MESSAGES = 20;
    /** Default maxLength for truncate-args. */
    public static final int DEFAULT_MAX_LENGTH = 20_000;
    /** Default truncation text. */
    public static final String DEFAULT_TRUNCATION_TEXT = "...[truncated]";

    /**
     * Default summary prompt: LangChain-style
     * {@code DEFAULT_SUMMARY_PROMPT} with the deepagents media-reference
     * addendum spliced in just before the {@code <messages>} marker.
     * Mirrors the Python port's
     * {@code DEEPAGENTS_DEFAULT_SUMMARY_PROMPT}.
     */
    public static final String DEFAULT_SUMMARY_PROMPT = """
            <media_reference_information>

            Conversation history may include media reference tags, for example:
            <image url="/conversation_history/media/{hash}.png" />
            These tags mean the original message included media that was preserved at the referenced backend path.
            Treat the tag and path as part of the conversation context. Do not infer visual details that are not available from surrounding text.
            When the media could be important for future context, preserve the media reference in your summary.
            The model consuming the summary can call `read_file` on the referenced path if it needs to inspect the media.
            </media_reference_information>

            <messages>
            {messages}
            </messages>""";

    /**
     * Profile-based defaults derived from {@link #computeSummarizationDefaults}.
     * Exposed as a plain record so the test fixture can introspect the
     * trigger/keep pairs the Python port asserts.
     */
    public record SummarizationDefaults(
            ContextSize trigger,
            ContextSize keep,
            SummarizationTruncateArgs.TruncateArgsSettings truncateArgsSettings) {}

    /**
     * Compute model-aware defaults. Mirrors
     * {@code compute_summarization_defaults} in the Python port.
     *
     * <p>When the model exposes a {@code profile["max_input_tokens"]}
     * integer, returns fraction-based defaults scaled to that context
     * window; otherwise returns fixed token/message counts that
     * err on the conservative side to avoid overshooting.</p>
     */
    public static SummarizationDefaults computeSummarizationDefaults(BaseChatModel model) {
        Objects.requireNonNull(model, "model");
        Map<String, Object> profile = model.profile();
        if (profile != null
                && profile.get("max_input_tokens") instanceof Integer) {
            return new SummarizationDefaults(
                    ContextSize.fraction(DEFAULT_PROFILE_TRIGGER_FRACTION),
                    ContextSize.fraction(DEFAULT_PROFILE_KEEP_FRACTION),
                    new SummarizationTruncateArgs.TruncateArgsSettings(
                            ContextSize.fraction(DEFAULT_PROFILE_TRIGGER_FRACTION),
                            ContextSize.fraction(DEFAULT_PROFILE_KEEP_FRACTION),
                            DEFAULT_MAX_LENGTH,
                            DEFAULT_TRUNCATION_TEXT));
        }
        return new SummarizationDefaults(
                ContextSize.tokens(DEFAULT_FALLBACK_TRIGGER_TOKENS),
                ContextSize.messages(DEFAULT_FALLBACK_KEEP_MESSAGES),
                new SummarizationTruncateArgs.TruncateArgsSettings(
                        ContextSize.messages(DEFAULT_FALLBACK_TRUNCATE_ARGS_MESSAGES),
                        ContextSize.messages(DEFAULT_FALLBACK_TRUNCATE_ARGS_MESSAGES),
                        DEFAULT_MAX_LENGTH,
                        DEFAULT_TRUNCATION_TEXT));
    }

    /**
     * Build a {@link SummarizationMiddleware} with model-aware defaults.
     *
     * <p>Mirrors the Python
     * {@code create_summarization_middleware} factory. Throws
     * {@link IllegalArgumentException} when {@code model} is a
     * string (the Python port raises {@code TypeError}; the Java
     * port is stricter and refuses to accept a model spec that
     * needs runtime resolution).</p>
     */
    public static SummarizationMiddleware create(BaseChatModel model, BackendProtocol backend) {
        return create(model, backend, null, -1, null);
    }

    public static SummarizationMiddleware create(BaseChatModel model,
                                                  BackendProtocol backend,
                                                  String summaryPrompt) {
        return create(model, backend, summaryPrompt, -1, null);
    }

    public static SummarizationMiddleware create(BaseChatModel model,
                                                  BackendProtocol backend,
                                                  String summaryPrompt,
                                                  int trimTokensToSummarize) {
        return create(model, backend, summaryPrompt, trimTokensToSummarize, null);
    }

    /**
     * Full factory. All parameters after {@code backend} are
     * keyword-only in the Python port; the Java port expresses
     * that by exposing them only via dedicated overloads (no
     * positional callers can reach the optional knobs).
     */
    public static SummarizationMiddleware create(BaseChatModel model,
                                                  BackendProtocol backend,
                                                  String summaryPrompt,
                                                  int trimTokensToSummarize,
                                                  ContextSize.TokenCounter tokenCounter) {
        Objects.requireNonNull(model, "model");
        if (!(model instanceof BaseChatModel)) {
            throw new IllegalArgumentException(
                    "`create_summarization_middleware` expects a BaseChatModel instance, "
                            + "got " + model.getClass().getName()
                            + ". Resolve model strings via resolve_model() first.");
        }
        Objects.requireNonNull(backend, "backend");

        SummarizationDefaults defaults = computeSummarizationDefaults(model);
        String prompt = summaryPrompt == null
                ? DEFAULT_SUMMARY_PROMPT
                : summaryPrompt;
        int trim = trimTokensToSummarize < 0
                ? SummarizationPrompts.DEFAULT_TRIM_TOKEN_LIMIT
                : trimTokensToSummarize;
        return new SummarizationMiddleware(
                backend,
                defaults.trigger(),
                defaults.keep(),
                prompt,
                trim,
                -1,
                null,
                tokenCounter,
                defaults.truncateArgsSettings());
    }
}
