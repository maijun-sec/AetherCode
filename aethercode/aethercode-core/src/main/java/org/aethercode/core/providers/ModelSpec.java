package org.aethercode.core.providers;

/**
 * spec for a single model offered by a
 * {@link ProviderSpec}. Mirrors the renderer's
 * {@code ModelInfo} type and the
 * {@code MetricsCollector}'s pricing table.
 *
 * <p>Cost is per 1k tokens (input and output). The
 * renderer shows these in the Settings panel's
 * model picker. {@link #default} marks the
 * provider's recommended model — picked when the
 * user hasn't explicitly chosen one.
 *
 * <p>R136.4: the constructor now also takes
 * {@code maxOutput} so the engine can set the
 * {@code max_tokens} chat-completion param
 * precisely for the model. The MiniMax M3 family
 * advertises 512K output; Claude Sonnet 4.5 and
 * GPT-4o are 64K / 16K respectively. When the
 * caller doesn't know, they pass {@code maxOutput}
 * equal to {@code context} (the "as much as
 * possible" conservative default that lets the
 * model pick its own ceiling).
 *
 * <p>R283: per-model compaction override. When
 * non-null, the model's {@code compact} block
 * takes precedence over the provider's. Used
 * when a single provider hosts both small-context
 * (e.g. glm-4-flash 128k) and large-context (e.g.
 * the future glm-4-1m-context) models.
 */
public record ModelSpec(
        String id,
        double inputPer1k,
        double outputPer1k,
        int context,
        int maxOutput,
        boolean isDefault,
        CompactSpec compact
) {
    public ModelSpec {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("model id is required");
        }
        if (inputPer1k < 0 || outputPer1k < 0) {
            throw new IllegalArgumentException("cost cannot be negative");
        }
        if (context <= 0) {
            throw new IllegalArgumentException("context must be > 0, got " + context);
        }
        if (maxOutput <= 0) {
            // Default to context (conservative: let the model
            // pick its own ceiling). The renderer / engine
            // will clamp to whichever is smaller.
            maxOutput = context;
        }
    }

    /** R136.4: deprecated 6-arg overload, kept for
     *  backward compat with callers that don't track
     *  output ceiling separately. Delegates to the
     *  7-arg form with {@code maxOutput = context}
     *  (the "let the model pick" default) and
     *  {@code compact=null} (the engine falls back
     *  to the provider-level compact block, then to
     *  {@link org.aethercode.core.compact.CompactConfig#DEFAULT}).
     *
     *  @deprecated prefer the 7-arg form so the engine
     *    can set the chat-completion {@code max_tokens}
     *    correctly for the model AND honour per-model
     *    compaction overrides. */
    @Deprecated
    public ModelSpec(String id, double inputPer1k, double outputPer1k,
                     int context, int maxOutput, boolean isDefault) {
        this(id, inputPer1k, outputPer1k, context, maxOutput, isDefault, null);
    }

    /** R136.4: deprecated 5-arg overload, kept for
     *  backward compat with callers that don't track
     *  output ceiling separately. Delegates to the
     *  6-arg form with {@code maxOutput = context}.
     *
     *  @deprecated prefer the 6-arg form so the engine
     *    can set the chat-completion {@code max_tokens}
     *    correctly for the model. */
    @Deprecated
    public ModelSpec(String id, double inputPer1k, double outputPer1k,
                     int context, boolean isDefault) {
        this(id, inputPer1k, outputPer1k, context, context, isDefault);
    }

    /** Convenience for "unknown cost" models (free
     *  local models, foreign brands we don't track).
     *  Both context and maxOutput are taken from the
     *  caller; the engine will cap maxOutput to
     *  whatever the chat completion supports. */
    public static ModelSpec free(String id, int context) {
        return new ModelSpec(id, 0.0, 0.0, context, context, false, null);
    }

    /** R136.4: same as {@link #free(String, int)} but
     *  with a distinct output ceiling (used for
     *  models like Claude Sonnet 4.5 where output
     *  is 64K but context is 200K). */
    public static ModelSpec free(String id, int context, int maxOutput) {
        return new ModelSpec(id, 0.0, 0.0, context, maxOutput, false, null);
    }

    /** R283: free + explicit per-model compact config
     *  (e.g. for ollama models that disable compaction
     *  because the compactor's LLM round-trip costs
     *  more than the saved context tokens). */
    public static ModelSpec free(String id, int context,
                                 CompactSpec compact) {
        return new ModelSpec(id, 0.0, 0.0, context, context, false, compact);
    }
}
