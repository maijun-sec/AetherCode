package org.aethercode.tasks.engine.summary;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for the {@code ## Summary} block convention.
 *
 * <p>The renderer expects every assistant turn to end with a
 * {@code ## Summary} heading followed by 1-3 lines of text. The
 * system prompt instructs the LLM to write this. The
 * {@link LlmBackedSummaryHook} uses the same convention to
 * detect whether the LLM forgot the footer.
 *
 * <p>Robust to:
 * <ul>
 *   <li>trailing / leading whitespace,</li>
 *   <li>level-1 (#) or level-3 (###) heading variants,</li>
 *   <li>no trailing newline.</li>
 * </ul>
 */
public final class SummaryExtractor {

    /**
     * Match a "## Summary" (or "# Summary" / "### Summary") block
     * at the end of the text. Non-greedy so a stray "## Summary"
     * in the middle of the message doesn't get caught.
     */
    private static final Pattern SUMMARY_HEADING = Pattern.compile(
            "(?ms)\\s*#{1,3}\\s*Summary\\s*:?\\s*\\n(?<body>.*?)\\s*\\z");

    /** Plain heading detection (no body extraction). */
    private static final Pattern HAS_SUMMARY = Pattern.compile(
            "(?m)\\s*#{1,3}\\s*Summary\\s*:?\\s*$");

    private SummaryExtractor() { }

    /** True iff {@code text} contains a {@code ## Summary} (or
     *  level-1/3 variant) heading. */
    public static boolean hasSummaryBlock(String text) {
        if (text == null || text.isBlank()) return false;
        return HAS_SUMMARY.matcher(text).find();
    }

    /**
     * Extract the summary body, or empty if not present. The
     * body is whatever follows the heading up to the next
     * heading or end of text.
     */
    public static String extractSummary(String text) {
        if (text == null || text.isBlank()) return "";
        Matcher m = SUMMARY_HEADING.matcher(text);
        if (!m.find()) return "";
        String body = m.group("body");
        return body == null ? "" : body.strip();
    }

    /**
     * Render an injected summary footer that the
     * {@code LlmBackedSummaryHook} appends when the LLM
     * forgot to write one.
     */
    public static String renderInjectedFooter(String summaryText) {
        if (summaryText == null) summaryText = "";
        return "\n\n## Summary\n" + summaryText.strip() + "\n";
    }

    /**
     * Render the human-readable fallback for an auto-summary
     * failure. The exact wording matches the spec:
     * <em>Summary: (LLM auto-summary failed — see last assistant message)</em>.
     */
    public static String renderFailureFooter(String reason) {
        if (reason == null || reason.isBlank()) {
            return "Summary: (LLM auto-summary failed — see last assistant message)\n";
        }
        return "Summary: (LLM auto-summary failed: " + reason + ")\n";
    }
}
