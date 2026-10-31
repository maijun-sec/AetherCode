/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

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
