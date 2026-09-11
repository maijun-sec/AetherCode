package org.aethercode.core.runtime.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;


/**
 * Declarative harness-profile config for YAML/JSON-backed profiles.
 *
 * <p>Java-native port of
 * {@code deepagents.profiles.harness.harness_profiles.HarnessProfileConfig}.
 * A {@code HarnessProfileConfig} contains the file-friendly subset
 * of harness settings: plain strings, bools, lists, and nested
 * maps that can be loaded from YAML or JSON. For in-code/runtime
 * adjustments such as {@code extraMiddleware} or class-form
 * {@code excludedMiddleware}, use {@link HarnessProfile} instead.</p>
 *
 * <p>Config objects may be passed directly to
 * {@link HarnessProfile#registerHarnessProfile(String, HarnessProfileConfig)};
 * the helper converts them to runtime {@link HarnessProfile} objects
 * automatically.</p>
 */
public record HarnessProfileConfig(
        String baseSystemPrompt,
        String systemPromptSuffix,
        Map<String, String> toolDescriptionOverrides,
        Set<String> excludedTools,
        Set<String> excludedMiddleware,
        GeneralPurposeSubagentProfile generalPurposeSubagent) {

    private static final Set<String> KNOWN_KEYS = Set.of(
            "base_system_prompt",
            "system_prompt_suffix",
            "tool_description_overrides",
            "excluded_tools",
            "excluded_middleware",
            "general_purpose_subagent");

    public HarnessProfileConfig {
        toolDescriptionOverrides = toolDescriptionOverrides == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(toolDescriptionOverrides));
        excludedTools = excludedTools == null
                ? Set.of()
                : Collections.unmodifiableSet(new java.util.LinkedHashSet<>(excludedTools));
        excludedMiddleware = excludedMiddleware == null
                ? Set.of()
                : Collections.unmodifiableSet(new java.util.LinkedHashSet<>(excludedMiddleware));
        // Grammar-check string entries the same way the runtime
        // `HarnessProfile` does, so config files fail fast on
        // typos instead of waiting until assembly.
        for (String entry : excludedMiddleware) {
            HarnessProfile.validateConfigMiddlewareString(entry, "excluded_middleware");
        }
    }

    /** Convert this declarative config into a runtime {@link HarnessProfile}. */
    public HarnessProfile toHarnessProfile() {
        return new HarnessProfile(
                baseSystemPrompt,
                systemPromptSuffix,
                toolDescriptionOverrides,
                excludedTools,
                excludedMiddleware,
                java.util.List.of(),  // extraMiddleware default empty
                generalPurposeSubagent);
    }

    /** Dump this config to plain map/list/scalar values. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (baseSystemPrompt != null) out.put("base_system_prompt", baseSystemPrompt);
        if (systemPromptSuffix != null) out.put("system_prompt_suffix", systemPromptSuffix);
        if (!toolDescriptionOverrides.isEmpty()) {
            // Re-coerce at serialize time so a config built via
            // the direct constructor (bypassing fromMap) still
            // fails on non-string keys/values. Mirrors the
            // Python port's `_coerce_str_mapping` call inside
            // `to_dict`.
            out.put("tool_description_overrides",
                    coerceStringMap(toolDescriptionOverrides, "tool_description_overrides"));
        }
        if (!excludedTools.isEmpty()) {
            // Sorted list for deterministic ordering regardless
            // of construction order — matches the Python port
            // which emits `sorted(self.excluded_tools)`.
            out.put("excluded_tools", sortedList(excludedTools));
        }
        if (!excludedMiddleware.isEmpty()) {
            out.put("excluded_middleware", sortedList(excludedMiddleware));
        }
        if (generalPurposeSubagent != null) {
            // Emit the key even when the sub-profile has no fields set so
            // fromMap(toMap(c)) preserves the "explicit empty sub-profile"
            // vs. "no sub-profile" distinction.
            out.put("general_purpose_subagent", generalPurposeSubagent.toMap());
        }
        return out;
    }

    private static List<String> sortedList(java.util.Collection<String> values) {
        List<String> out = new java.util.ArrayList<>(values);
        java.util.Collections.sort(out);
        return out;
    }

    /** Construct a config from a plain map. */
    @SuppressWarnings("unchecked")
    public static HarnessProfileConfig fromMap(Map<String, Object> data) {
        if (data == null) return new HarnessProfileConfig(null, null, Map.of(), Set.of(),
                Set.of(), null);
        Set<String> unknown = new java.util.HashSet<>(data.keySet());
        unknown.removeAll(KNOWN_KEYS);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown keys in HarnessProfileConfig map: " + unknown);
        }
        return new HarnessProfileConfig(
                coerceStringOrNull(data.get("base_system_prompt"), "base_system_prompt"),
                coerceStringOrNull(data.get("system_prompt_suffix"), "system_prompt_suffix"),
                coerceStringMap(data.get("tool_description_overrides"),
                        "tool_description_overrides"),
                coerceStringSet(data.get("excluded_tools"), "excluded_tools"),
                coerceStringSet(data.get("excluded_middleware"), "excluded_middleware"),
                coerceGeneralPurpose(data.get("general_purpose_subagent")));
    }

    /** Export a runtime {@link HarnessProfile} back to declarative config. */
    public static HarnessProfileConfig fromHarnessProfile(HarnessProfile profile) {
        if (profile == null) return new HarnessProfileConfig(null, null, Map.of(),
                Set.of(), Set.of(), null);
        // Materialize so a non-empty factory is also rejected (the
        // static-list form is the cheap path).
        List<Object> materialized = profile.materializeExtraMiddleware();
        if (profile.extraMiddleware() instanceof Supplier<?>
                || !materialized.isEmpty()) {
            throw new IllegalArgumentException(
                    "HarnessProfileConfig.fromHarnessProfile() cannot export "
                            + "`extraMiddleware`. Object instances and factories are "
                            + "runtime-only; keep them in `HarnessProfile`.");
        }
        return new HarnessProfileConfig(
                profile.baseSystemPrompt(),
                profile.systemPromptSuffix(),
                profile.toolDescriptionOverrides(),
                profile.excludedTools(),
                profile.excludedMiddleware(),
                profile.generalPurposeSubagent());
    }

    // -- coercion helpers (mirrors Python's _coerce_* helpers) -----

    private static String coerceStringOrNull(Object value, String fieldName) {
        if (value == null) return null;
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(
                    "`" + fieldName + "` must be String or null, got "
                            + value.getClass().getSimpleName());
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> coerceStringMap(Object value, String fieldName) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException(
                    "`" + fieldName + "` must be a mapping, got "
                            + value.getClass().getSimpleName());
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String) || !(e.getValue() instanceof String)) {
                throw new IllegalArgumentException(
                        "`" + fieldName + "` keys and values must be strings");
            }
            out.put((String) e.getKey(), (String) e.getValue());
        }
        return out;
    }

    private static Set<String> coerceStringSet(Object value, String fieldName) {
        if (value == null) return Set.of();
        if (!(value instanceof Iterable<?> it)) {
            throw new IllegalArgumentException(
                    "`" + fieldName + "` must be a list/set of strings, got "
                            + value.getClass().getSimpleName());
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Object entry : it) {
            if (!(entry instanceof String s)) {
                throw new IllegalArgumentException(
                        "`" + fieldName + "` entries must be strings, got "
                                + (entry == null ? "null" : entry.getClass().getSimpleName()));
            }
            out.add(s);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static GeneralPurposeSubagentProfile coerceGeneralPurpose(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?> m) {
            return GeneralPurposeSubagentProfile.fromMap((Map<String, Object>) m);
        }
        throw new IllegalArgumentException(
                "`general_purpose_subagent` must be a mapping, got "
                        + value.getClass().getSimpleName());
    }
}
