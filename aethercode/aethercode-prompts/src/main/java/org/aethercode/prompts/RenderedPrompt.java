package org.aethercode.prompts;

import java.util.List;

/**
 * structured result of {@link SystemPrompt#renderWithSources()}.
 *
 * <p>The plain {@link SystemPrompt#render()} method concatenates
 * every section into a single string and returns it. That is
 * what the LLM sees on the wire, and what 99% of callers want.
 * But for debug, audit, and a future "show me what the model
 * actually saw" UI panel, it is useful to know where each
 * section came from: was the rules block empty (so the section
 * was omitted)? Did the tool list come from the engine's
 * default pool, or from a per-agent override? Where did the
 * memory recall pull its hits from?
 *
 * <p>{@code RenderedPrompt} captures that provenance. The
 * {@link #text()} field is the same string {@code render()}
 * would have produced — callers that only need the
 * concatenation can use this directly. The {@link #sections()}
 * field is a list of {@link Section} records, one per
 * non-empty slot in the prompt, in the same order the text
 * concatenates them.
 *
 * <p>Sections are emitted in the same order the prompt is
 * assembled (identity, rules, environment, tooling, workflow,
 * planMode, memory). A section is omitted from the list when
 * the corresponding builder field is null, blank, or produced
 * an empty string after stripping — the same rule {@code
 * render()} uses. The {@code source} label is free-form and
 * meant for humans; the canonical verbs are
 *
 * <ul>
 *   <li>{@code "default"} — the section came from
 *       {@code SystemPrompt.defaultIdentity()} /
 *       {@code SystemPrompt.defaultWorkflow()}.</li>
 *   <li>{@code "builder"} — the section was set explicitly
 *       via a {@code SystemPrompt.Builder} method.</li>
 *   <li>{@code "rules:<path>"} — the section was loaded from
 *       a {@code .aethercode/rules/*.md} file (prior round).</li>
 *   <li>{@code "memory"} — the section came from the
 *       per-query memory recall.</li>
 *   <li>{@code "plan-mode"} — the section was installed by
 *       {@code QueryEngine.setPlanModeSuffix}.</li>
 * </ul>
 *
 * <p>The labels are deliberately short so a debug dump fits on
 * one line per section.
 */
public record RenderedPrompt(String text, List<Section> sections) {

    /**
     * One slot in the rendered prompt. {@code name} is the
     * canonical slot name (matches the builder field name).
     * {@code text} is the section body that was concatenated
     * (already trimmed). {@code source} is a free-form
     * provenance label.
     */
    public record Section(String name, String text, String source) {
        public Section {
            // Defensive: a null name would make the section
            // hard to identify in a debug dump. The name
            // is required.
            if (name == null) name = "(unnamed)";
            if (text == null) text = "";
            if (source == null) source = "unknown";
        }
    }

    /** Convenience: a debug-friendly one-line summary per
     *  section. Used by the planned "show what the model saw"
     *  debug panel and by ad-hoc {@code toString} callers. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (Section s : sections) {
            int len = s.text().length();
            String firstLine = firstNonEmptyLine(s.text());
            sb.append("[").append(s.name()).append("]")
                    .append(" source=").append(s.source())
                    .append(" length=").append(len);
            if (!firstLine.isEmpty()) {
                sb.append(" first=\"").append(truncate(firstLine, 60)).append("\"");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String firstNonEmptyLine(String s) {
        if (s == null) return "";
        for (String line : s.split("\n")) {
            String t = line.strip();
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
