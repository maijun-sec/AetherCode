package org.aethercode.code.tui.widgets;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Shared click-to-copy span metadata for the deepagents-code TUI.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets._copy_spans}. The
 * Python module is a tiny utility that defines two metadata keys and a
 * build/extract pair, so the producer (a row widget) and the consumer (a
 * click handler) cannot drift apart. The Java port preserves the same
 * contract: a {@link #target(Map)} extractor returns the (text, label)
 * tuple when the metadata carries the markers, and a {@link #meta(String,
 * String)} builder produces the metadata map.</p>
 *
 * <p>These helpers are shared by the welcome banner and the debug console
 * snapshot. Both call sites render {@code label: value} rows where the
 * value span is click-to-copy.</p>
 */
public final class CopySpans {

    /** Meta key marking a span whose text is copied on click. */
    public static final String COPY_TEXT_META = "copy_text";

    /** Meta key carrying the field label used in the copy toast. */
    public static final String COPY_LABEL_META = "copy_label";

    private CopySpans() {}

    /**
     * Build the metadata map that marks a span as click-to-copy.
     *
     * @param text  The text copied to the clipboard when the span is clicked.
     * @param label The field label used to word the success toast.
     * @return A map carrying only the copy metadata. Combine with a visual
     *         style at the call site to produce the final span.
     */
    public static Map<String, String> meta(String text, String label) {
        return Map.of(COPY_TEXT_META, text, COPY_LABEL_META, label);
    }

    /**
     * Return the copy text and field label from a span style map, if any.
     *
     * <p>Mirrors the Python {@code copy_span_target} function. Both
     * {@code text} and {@code label} must be non-empty strings; otherwise
     * the metadata is treated as malformed and {@link Optional#empty()}
     * is returned.</p>
     *
     * @param meta The map under the click target. May be {@code null}.
     * @return The (text, label) tuple when present and well-formed, else
     *         empty.
     */
    public static Optional<Target> target(Map<String, Object> meta) {
        if (meta == null) return Optional.empty();
        Object textRaw = meta.get(COPY_TEXT_META);
        Object labelRaw = meta.get(COPY_LABEL_META);
        if (!(textRaw instanceof String text) || text.isEmpty()) {
            return Optional.empty();
        }
        if (!(labelRaw instanceof String label) || label.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Target(text, label));
    }

    /** Build a {@link WidgetNode.CopySpan} node from the given values. */
    public static WidgetNode span(String text, String label) {
        return new WidgetNode.CopySpan(Objects.requireNonNull(text), Objects.requireNonNull(label));
    }

    /** A successful extraction from {@link #target(Map)}. */
    public record Target(String text, String label) {}
}
