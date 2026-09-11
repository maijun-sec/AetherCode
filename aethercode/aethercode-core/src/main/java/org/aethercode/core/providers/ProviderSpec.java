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
}
