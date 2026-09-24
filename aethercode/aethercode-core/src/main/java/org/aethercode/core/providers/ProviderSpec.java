package org.aethercode.core.providers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *
 * <p><b>R341 — bundled YAML + extended fields</b>:
 * <ul>
 *   <li>{@code enabled} — when {@code false} the
 *     renderer hides the provider entirely (a model
 *     the user can't call should not appear in the
 *     picker). Defaults to {@code true}.</li>
 *   <li>{@code headers} — extra HTTP headers sent
 *     with every request to this provider (e.g.
 *     {@code X-Trace-Id} for tracing, or vendor
 *     custom headers). Defaults to empty.</li>
 *   <li>{@code timeout} / {@code connectTimeout}
 *     — Spring AI timeout overrides in milliseconds.
 *     {@code null} means "use Spring default".</li>
 *   <li>{@code apiKey} — optional inline API key.
 *     When present, takes priority over the env-var
 *     fallback chain. {@link #apiKey()} resolves the
 *     full 4-step chain (yaml inline → yaml apiKeyEnv
 *     with 3-scope env → provider-name-derived
 *     {@code <NAME>_API_KEY} → null).</li>
 * </ul>
 */
public record ProviderSpec(
        String name,
        String type,
        String baseUrl,
        String apiKeyEnv,
        String defaultModel,
        List<ModelSpec> models,
        CompactSpec compact,
        List<Variant> variants,
        boolean enabled,
        Map<String, String> customHeaders,
        Integer timeoutMs,
        Integer connectTimeoutMs,
        String apiKey
) {
    /** R341: 13-arg canonical constructor (current). */
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
        // Defensive copies.
        models = List.copyOf(models);
        customHeaders = (customHeaders == null || customHeaders.isEmpty())
                ? Collections.emptyMap()
                : Map.copyOf(customHeaders);
    }

    /** R341: 8-arg overload (R283 baseline). Maps to the 13-arg form
     *  with {@code enabled=true}, no headers, no timeouts, no inline
     *  apiKey. {@code variants=null} keeps the model-level fallback
     *  chain intact. Kept so existing test fixtures and call sites
     *  keep compiling. */
    @Deprecated(since = "R341")
    public ProviderSpec(String name, String type, String baseUrl,
                        String apiKeyEnv, String defaultModel,
                        List<ModelSpec> models,
                        CompactSpec compact) {
        this(name, type, baseUrl, apiKeyEnv, defaultModel, models, compact, null,
                true, Map.of(), null, null, null);
    }

    /** R341: 8-arg overload with variants (R285 baseline). Maps to
     *  the 13-arg form with the same defaults as the 7-arg overload. */
    @Deprecated(since = "R341")
    public ProviderSpec(String name, String type, String baseUrl,
                        String apiKeyEnv, String defaultModel,
                        List<ModelSpec> models,
                        CompactSpec compact,
                        List<Variant> variants) {
        this(name, type, baseUrl, apiKeyEnv, defaultModel, models, compact, variants,
                true, Map.of(), null, null, null);
    }

    /** R283: legacy 6-arg overload (without compact or variants).
     *  Kept so existing test fixtures and user providers.yaml
     *  files keep working. */
    @Deprecated(since = "R283")
    public ProviderSpec(String name, String type, String baseUrl,
                        String apiKeyEnv, String defaultModel,
                        List<ModelSpec> models) {
        this(name, type, baseUrl, apiKeyEnv, defaultModel, models, null, null,
                true, Map.of(), null, null, null);
    }

    /** R341: resolve the API key with the canonical 4-step chain:
     * <ol>
     *   <li>this provider's inline {@code apiKey} field (highest
     *       priority; the user explicitly chose to embed it)</li>
     *   <li>this provider's {@code apiKeyEnv} via
     *       {@link RegistryHelper#readEnv(String)} — Process → User →
     *       Machine scope lookup</li>
     *   <li>provider-name-derived env var: {@code <UPPER_NAME>_API_KEY}
     *       (e.g. {@code GLM_API_KEY} for the {@code glm} brand) via
     *       the same 3-scope lookup</li>
     *   <li>{@code null} when nothing resolved</li>
     * </ol>
     * Returns {@code null} when no source has a non-blank value (the
     * renderer's Settings panel uses {@link #hasApiKey()} to decide
     * whether to show or hide the provider). */
    public String apiKey() {
        // Step 1: yaml inline
        if (apiKey != null && !apiKey.isBlank()) return apiKey;
        // Step 2: yaml apiKeyEnv (when set to a real var name)
        if (apiKeyEnv != null && !apiKeyEnv.isBlank() && !"API_KEY".equals(apiKeyEnv)) {
            String k = RegistryHelper.readEnv(apiKeyEnv);
            if (k != null) return k;
        }
        // Step 3: derived from provider name
        String derived = deriveDefaultApiKeyEnv();
        if (!derived.equals(apiKeyEnv)) {
            String k = RegistryHelper.readEnv(derived);
            if (k != null) return k;
        }
        // Step 4: nothing
        return null;
    }

    /** Default API key env-var name derived from the provider's
     *  {@code name}: uppercased + {@code _API_KEY} suffix
     *  (e.g. {@code glm} → {@code GLM_API_KEY}). Returns the
     *  original {@code apiKeyEnv} when the name is null/blank
     *  (degenerate but defensive). */
    String deriveDefaultApiKeyEnv() {
        if (name == null || name.isBlank()) return apiKeyEnv != null ? apiKeyEnv : "API_KEY";
        return name.toUpperCase().replace("-", "_") + "_API_KEY";
    }

    /** R282 + R341: true when {@link #apiKey()} resolves to a
     *  non-blank value. The Settings panel filters the picker so
     *  providers with no key are hidden (R341 constitution rule),
     *  unless the provider is otherwise reachable (submodels are
     *  not shown for unconfigured providers). */
    public boolean hasApiKey() {
        String k = apiKey();
        return k != null && !k.isBlank();
    }

    /** R341: true when the renderer should expose this provider in
     *  the picker. Combines the explicit {@code enabled} flag with
     *  the implicit "has an API key" check — a provider the user
     *  can't call AND hasn't explicitly enabled is hidden. The
     *  explicit {@code enabled: true} in user YAML can override a
     *  missing key (e.g. the user wants the picker to show "ollama"
     *  even though ollama doesn't need a key). */
    public boolean isSelectable() {
        return enabled && (hasApiKey() || apiKeyEnv == null
                || apiKeyEnv.isBlank() || "API_KEY".equals(apiKeyEnv));
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

    /** R285: look up the variant for a specific model
     *  by name. The lookup order is:
     *  <ol>
     *    <li>{@code model.variants[name]} (per-model
     *        override)</li>
     *    <li>this provider's {@code variants} list
     *        (provider-level fallback — applies to
     *        every sibling model that doesn't declare
     *        its own variant block)</li>
     *    <li>{@link Variant#BUILTIN} (the bundled
     *        claude-code-style low/medium/high/xhigh
     *        set — every model gets SOMETHING
     *        renderable in the picker)</li>
     *  </ol>
     *  Returns the runtime {@link Variant} shape
     *  (post-defaults-fill) so callers never see a
     *  null. */
    public Variant variantFor(String modelId, String name) {
        for (ModelSpec m : models) {
            if (m.id().equals(modelId)) {
                for (Variant v : m.variants()) {
                    if (name != null && name.equalsIgnoreCase(v.name())) {
                        return v;
                    }
                }
                break;
            }
        }
        // model not found in list OR didn't carry this
        // variant — fall back to the provider-level
        // block (if any).
        if (variants != null) {
            for (Variant v : variants) {
                if (name != null && name.equalsIgnoreCase(v.name())) {
                    return v;
                }
            }
        }
        // last resort: the bundled set. The renderer's
        // Quality dropdown always has something to
        // show even when the providers.yaml is
        // totally bare.
        for (Variant v : Variant.BUILTIN) {
            if (name != null && name.equalsIgnoreCase(v.name())) {
                return v;
            }
        }
        return Variant.DEFAULT;
    }

    private static org.aethercode.core.compact.CompactConfig tierDefaultFor(int ctx) {
        return org.aethercode.core.compact.CompactConfig.forContextWindow(ctx);
    }
}