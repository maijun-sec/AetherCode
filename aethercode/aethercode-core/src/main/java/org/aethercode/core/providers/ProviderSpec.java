package org.aethercode.core.providers;

import java.util.List;

/**
 * spec for a single LLM provider. AetherCode
 * uses spring-ai's {@code OpenAiChatModel} for every
 * provider — the differentiation is the
 * {@code baseUrl} and {@code apiKeyEnv}. The
 * {@code type} field is for future expansion
 * (e.g. an Anthropic-native path), but the current
 * implementation treats every provider as
 * OpenAI-compatible.
 *
 * <p>The {@code defaultModel} is the model picked
 * when the user hasn't chosen one explicitly.
 * Each {@link ModelSpec} in {@code models} declares
 * its context window and pricing so the renderer
 * can show it in the Settings picker and the
 * metrics module can bill the user.
 *
 * <p>The {@code compact} block (R283) declares the
 * provider-level compaction defaults. Individual
 * models can override via {@link ModelSpec#compact()}.
 *
 * <p>Provider specs are typically loaded from
 * {@code ~/.aethercode/providers.yaml} (or the
 * bundled default if that file is missing) by
 * {@link ProviderRegistry}. Tests can construct
 * an in-memory registry directly.
 */
public record ProviderSpec(
        String name,
        String type,
        String baseUrl,
        String apiKeyEnv,
        String defaultModel,
        List<ModelSpec> models,
        CompactSpec compact
) {
    /** Legacy 6-arg overload so existing test fixtures and
     *  user providers.yaml files keep working. Maps to
     *  the 7-arg form with {@code compact=null} (the
     *  accessor then falls back to the model-level
     *  compact block, then to
     *  {@link org.aethercode.core.compact.CompactConfig#DEFAULT}). */
    public ProviderSpec(String name, String type, String baseUrl,
                        String apiKeyEnv, String defaultModel,
                        List<ModelSpec> models) {
        this(name, type, baseUrl, apiKeyEnv, defaultModel, models, null);
    }

    public ProviderSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("provider name is required");
        }
        if (type == null) type = "openai-compat";
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("provider baseUrl is required for: " + name);
        }
        if (apiKeyEnv == null) apiKeyEnv = "API_KEY";
        if (models == null || models.isEmpty()) {
            throw new IllegalArgumentException("provider must declare at least one model: " + name);
        }
        // Validate the default model is in the list.
        if (defaultModel != null && !defaultModel.isBlank()) {
            boolean found = models.stream().anyMatch((m) -> defaultModel.equals(m.id()));
            if (!found) {
                throw new IllegalArgumentException(
                        "provider " + name + " defaultModel " + defaultModel
                                + " not in models list");
            }
        }
        // Defensive copy.
        models = List.copyOf(models);
    }

    /** Resolved at call time so tests can swap env
     *  vars between calls. Returns null when the env
     *  var is unset (caller decides what to do —
     *  usually surface as "API key not set" in the
     *  renderer's error pill). */
    public String apiKey() {
        return System.getenv(apiKeyEnv);
    }

    /** R282: true when {@link #apiKeyEnv} resolves to a
     *  non-blank value in the current process environment.
     *  The renderer's Settings panel uses this to filter
     *  the model picker so providers the user hasn't
     *  configured (no API key in env) don't show up —
     *  otherwise the user sees a list of models they
     *  can't actually call. Submodels of a configured
     *  provider are still shown (the user might not have
     *  purchased every model, but they CAN call it once
     *  their account is set up). The {@link ProviderSpec#apiKeyEnv}
     *  is the env-var name declared on the spec; the lookup
     *  is dynamic (env vars can change between calls in
     *  tests). */
    public boolean hasApiKey() {
        String k = apiKey();
        return k != null && !k.isBlank();
    }

    /** R283: look up the compaction configuration for
     *  a specific model id. The lookup order is
     *  <ol>
     *    <li>the model's own {@code compact} block</li>
     *    <li>this provider's {@code compact} block</li>
     *    <li>{@link org.aethercode.core.compact.CompactConfig#DEFAULT}
     *        — the last-resort tier default for a 200k window</li>
     *  </ol>
     *  Returns the runtime
     *  {@link org.aethercode.core.compact.CompactConfig}
     *  form (the YAML {@link CompactSpec} is converted
     *  eagerly here so callers never see a "compact
     *  missing" branch). */
    public org.aethercode.core.compact.CompactConfig compactFor(String modelId) {
        for (ModelSpec m : models) {
            if (m.id().equals(modelId)) {
                if (m.compact() != null) return m.compact().toConfig();
                if (compact != null) return compact.toConfig();
                return tierDefaultFor(contextWindow());
            }
        }
        return org.aethercode.core.compact.CompactConfig.DEFAULT;
    }

    /** Default compact tier when neither the model
     *  nor the provider declared a compact block.
     *  Picks a sensible buffer based on the model's
     *  own context window. */
    public int contextWindow() {
        // pick the max across all models as the
        // provider-level context (used as fallback
        // by compactFor when nothing else matches).
        int max = 0;
        for (ModelSpec m : models) {
            if (m.context() > max) max = m.context();
        }
        return max > 0 ? max : org.aethercode.core.compact.CompactConfig.DEFAULT.contextWindow();
    }

    private static org.aethercode.core.compact.CompactConfig tierDefaultFor(int ctx) {
        return org.aethercode.core.compact.CompactConfig.forContextWindow(ctx);
    }
}
