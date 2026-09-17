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
        List<ModelSpec> models
) {
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
}
