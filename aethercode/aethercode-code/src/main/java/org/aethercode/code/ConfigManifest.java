package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration manifest.
 *
 * <p>Java-native port of the Python {@code deepagents_code.config_manifest}
 * module. The Java port exposes the canonical list of config keys
 * the TUI shows in {@code /config show}.</p>
 */
public final class ConfigManifest {
    private ConfigManifest() {}

    /** A manifest entry. */
    public record Entry(
            String key,
            String type,
            String defaultValue,
            String description) {
    }

    /** The full manifest. */
    public static final List<Entry> ENTRIES = List.of(
            new Entry("default_model", "string", null, "Default model spec"),
            new Entry("auto_classifier_model", "string", null, "Classifier model spec"),
            new Entry("interpreter_enabled", "boolean", "false", "Whether shell execution is enabled"),
            new Entry("yolo_switcher", "boolean", "true", "Whether YOLO appears in the approval-mode cycle"),
            new Entry("memory_auto_save", "boolean", "true", "Auto-save memory to disk on session end"),
            new Entry("rubric_max_iterations", "int", "3", "Max rubric grading iterations"),
            new Entry("openai_prompt_cache_key", "boolean", "true", "Use OpenAI prompt cache key when supported"),
            new Entry("langsmith_redaction", "boolean", "false", "Redact secrets from LangSmith traces"));

    /** All entries keyed by config key. */
    public static Map<String, Entry> byKey() {
        Map<String, Entry> out = new LinkedHashMap<>();
        for (Entry e : ENTRIES) out.put(e.key(), e);
        return out;
    }
}
