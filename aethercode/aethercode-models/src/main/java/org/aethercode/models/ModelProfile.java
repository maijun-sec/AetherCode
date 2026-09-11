package org.aethercode.models;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Phase 2.2 / T-2-13 (design.md §3.7, spec.md §10.1): one
 * model definition, as exposed by the {@code model/list} and
 * {@code model/get} RPCs and rendered by the TUI's
 * {@code ModelSelector} / Desktop's {@code ModelPicker}.
 *
 * <p>Field semantics:
 * <ul>
 *   <li>{@code name} — the global, unique model id (e.g. {@code "claude-opus-4-1"}).
 *       This is the key the registry indexes on; the provider
 *       field is informational.</li>
 *   <li>{@code provider} — the provider key (e.g. {@code "anthropic"}). Used for
 *       grouping in the picker and for routing the actual API call.</li>
 *   <li>{@code displayName} — human-friendly label for menus. Defaults to {@code name}
 *       when the YAML omits it.</li>
 *   <li>{@code contextWindow} — the model's effective context window in tokens.
 *       Used by the {@code ContextMeter} (spec.md §7.1) for the percent-full
 *       bar.</li>
 *   <li>{@code maxOutput} — the model's max output token count per call.
 *       Used to cap the {@code --max-tokens} CLI flag.</li>
 *   <li>{@code capabilities} — feature flags: {@code vision}, {@code tools},
 *       {@code json-mode}, {@code streaming}, etc. Free-form strings; the
 *       known set is the union of what the TUI/Desktop render badges for.</li>
 *   <li>{@code pricing} — input/output/cached $ per 1M tokens. See
 *       {@link Pricing#costUsd}.</li>
 * </ul>
 *
 * <p>This is a pure value type. Mutations are not supported;
 * use {@link #withPricing(Pricing)} / {@link #withActive(boolean)}
 * to derive a new copy.
 */
public record ModelProfile(
        String name,
        String provider,
        String displayName,
        int contextWindow,
        int maxOutput,
        Set<String> capabilities,
        Pricing pricing,
        Map<String, Object> metadata
) {
    public ModelProfile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(provider, "provider");
        if (name.isBlank()) throw new IllegalArgumentException("model name is blank");
        if (provider.isBlank()) throw new IllegalArgumentException("model provider is blank");
        if (displayName == null || displayName.isBlank()) displayName = name;
        if (contextWindow < 0) {
            throw new IllegalArgumentException("contextWindow must be >= 0, got " + contextWindow);
        }
        if (maxOutput < 0) {
            throw new IllegalArgumentException("maxOutput must be >= 0, got " + maxOutput);
        }
        if (capabilities == null) capabilities = Set.of();
        capabilities = Set.copyOf(capabilities); // defensive copy + immutability
        if (pricing == null) pricing = Pricing.free();
        if (metadata == null) metadata = Map.of();
        else metadata = Map.copyOf(metadata);
    }

    /** Convenience constructor for tests / hand-rolled profiles. */
    public static ModelProfile of(String name, String provider, int contextWindow, int maxOutput) {
        return new ModelProfile(name, provider, name, contextWindow, maxOutput,
                Set.of(), Pricing.free(), Map.of());
    }

    /** True iff this profile has all of the named capabilities. */
    public boolean hasCapabilities(String... required) {
        if (required == null || required.length == 0) return true;
        for (String r : required) {
            if (!capabilities.contains(r)) return false;
        }
        return true;
    }

    /** Sorted snapshot of the capability set (handy for display + tests). */
    public List<String> sortedCapabilities() {
        return capabilities.stream().sorted().toList();
    }

    /** Returns a copy with {@code pricing} replaced. */
    public ModelProfile withPricing(Pricing newPricing) {
        return new ModelProfile(name, provider, displayName, contextWindow, maxOutput,
                capabilities, newPricing, metadata);
    }

    /** Returns a copy with one metadata field added. Used by the registry's
     *  {@code active: <name>} merge layer. */
    public ModelProfile withMetadata(String key, Object value) {
        java.util.Map<String, Object> next = new java.util.LinkedHashMap<>(metadata);
        next.put(key, value);
        return new ModelProfile(name, provider, displayName, contextWindow, maxOutput,
                capabilities, pricing, next);
    }

    @Override
    public String toString() {
        return "ModelProfile(" + provider + "/" + name
                + ", ctx=" + contextWindow
                + ", maxOut=" + maxOutput
                + ", caps=" + capabilities
                + ", " + pricing + ")";
    }
}
