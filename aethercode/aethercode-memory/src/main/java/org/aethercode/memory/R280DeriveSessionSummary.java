package org.aethercode.memory;

import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;

import java.util.List;

/**
 * R280: derive a one-line change-log summary from a finished
 * session's transcript. Heuristic, no LLM call.
 *
 * <p>Heuristic (in priority order):
 * <ol>
 *   <li>Last assistant message's text content. Strip leading whitespace,
 *       collapse inner newlines, truncate to {@link #MAX_LEN}. If empty
 *       after stripping, fall through.</li>
 *   <li>First user message's text content. Same treatment. Useful when
 *       a session ended on a tool error without a final assistant
 *       message.</li>
 *   <li>If neither produced text, fall back to
 *       {@code "session completed (<N> tool calls, <M> messages)"}.</li>
 * </ol>
 *
 * <p>The returned string is a single line; newlines are replaced with
 * spaces so the change-log stays one-entry-per-line on disk.
 */
public final class R280DeriveSessionSummary {

    /** Hard cap on summary length so the change-log stays scannable. */
    public static final int MAX_LEN = 140;
    /** Truncation suffix when the summary exceeds {@link #MAX_LEN}. */
    public static final String ELLIPSIS = "…";

    private R280DeriveSessionSummary() {}

    public static String fromTranscript(List<Message> transcript) {
        if (transcript == null || transcript.isEmpty()) return null;
        String s = lastAssistantText(transcript);
        if (s == null || s.isBlank()) s = firstUserText(transcript);
        if (s == null || s.isBlank()) {
            s = fallback(transcript);
        }
        return collapse(s);
    }

    /** Last assistant turn's text content. Returns null when no
     *  assistant turn has any text. */
    static String lastAssistantText(List<Message> transcript) {
        for (int i = transcript.size() - 1; i >= 0; i--) {
            Message m = transcript.get(i);
            if (m != null && m.role() == Role.ASSISTANT) {
                String t = m.textContent();
                if (t != null && !t.isBlank()) return t;
            }
        }
        return null;
    }

    /** First user turn's text content. Returns null when none. */
    static String firstUserText(List<Message> transcript) {
        for (Message m : transcript) {
            if (m != null && m.role() == Role.USER) {
                String t = m.textContent();
                if (t != null && !t.isBlank()) return t;
            }
        }
        return null;
    }

    /** Tool-call counts as a parenthetical. */
    private static String fallback(List<Message> transcript) {
        int tools = 0;
        for (Message m : transcript) {
            if (m == null) continue;
            for (var b : m.content()) {
                if (b == null) continue;
                String cn = b.getClass().getSimpleName();
                if (cn.contains("ToolUse") || cn.contains("ToolResult")) tools++;
            }
        }
        return "session completed (" + transcript.size() + " messages, " + tools + " tool calls)";
    }

    /** Collapse internal whitespace + truncate to MAX_LEN. */
    private static String collapse(String s) {
        if (s == null) return null;
        String out = s.replaceAll("\\s+", " ").trim();
        if (out.length() > MAX_LEN) {
            out = out.substring(0, MAX_LEN - 1) + ELLIPSIS;
        }
        return out;
    }
}
