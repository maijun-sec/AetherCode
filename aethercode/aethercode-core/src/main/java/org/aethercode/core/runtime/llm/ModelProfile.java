package org.aethercode.core.runtime.llm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Declarative profile describing a chat model's input/output
 * capabilities. Java-native port of LangChain's
 * {@code langchain_core.language_models.ModelProfile}.
 *
 * <p>A {@code ModelProfile} is a map of capability flags keyed by
 * field name (e.g. {@code "image_inputs"},
 * {@code "pdf_inputs"},
 * {@code "image_tool_message"}). The
 * {@link FilesystemMiddleware}-adjacent
 * {@code MultimodalContentScrubber} consults these flags to decide
 * whether a multimodal content block the
 * {@code read_file} tool emitted is supported by the
 * resolved model.</p>
 *
 * <p>Mirrors the Python port's behavior of treating absent /
 * non-{@code boolean} fields as "supported" (the default).
 * A field is only an "explicit rejection" when its value is
 * {@code Boolean.FALSE}; any other value &mdash; {@code true},
 * missing, or non-boolean &mdash; means "supported" so missing
 * profile coverage doesn't break existing flows.</p>
 */
public record ModelProfile(Map<String, Object> fields) {

    public ModelProfile {
        Objects.requireNonNull(fields, "fields");
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /** Convenience: an empty profile (every block type supported). */
    public static ModelProfile empty() {
        return new ModelProfile(Map.of());
    }

    /**
     * Whether a profile field explicitly rejects a feature.
     * Returns {@code true} only when the field is present AND
     * equals {@code Boolean.FALSE}. Missing / {@code true} /
     * non-boolean values are treated as "supported".
     */
    public boolean isFalse(String field) {
        Object v = fields.get(field);
        return v instanceof Boolean b && !b;
    }

    /** Whether {@code image_inputs} is explicitly rejected. */
    public boolean imageInputs() { return isFalse("image_inputs"); }

    /** Whether {@code pdf_inputs} is explicitly rejected. */
    public boolean pdfInputs() { return isFalse("pdf_inputs"); }

    /** Whether {@code audio_inputs} is explicitly rejected. */
    public boolean audioInputs() { return isFalse("audio_inputs"); }

    /** Whether {@code video_inputs} is explicitly rejected. */
    public boolean videoInputs() { return isFalse("video_inputs"); }

    /** Whether {@code image_tool_message} is explicitly rejected. */
    public boolean imageToolMessage() { return isFalse("image_tool_message"); }

    /** Whether {@code pdf_tool_message} is explicitly rejected. */
    public boolean pdfToolMessage() { return isFalse("pdf_tool_message"); }

    /** Build a {@code ModelProfile} with the supplied fields. */
    public static ModelProfile of(String k1, Object v1) {
        return new ModelProfile(Map.of(k1, v1));
    }

    /** Build a {@code ModelProfile} with the supplied fields. */
    public static ModelProfile of(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return new ModelProfile(m);
    }
}
