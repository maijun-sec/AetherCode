package org.aethercode.code;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tool display formatting for the TUI.
 *
 * <p>Java-native port of the Python {@code deepagents_code.tool_display}
 * module. The Java port exposes a small formatter API; the TUI host calls
 * it when rendering tool calls and results in the chat transcript.</p>
 */
public final class ToolDisplay {
    private ToolDisplay() {}

    /** Glyph set for the active terminal mode. */
    public record Glyphs(String bullet, String check, String cross, String ellipsis, String arrow) {
        public static Glyphs ascii() {
            return new Glyphs("*", "+", "x", "...", "->");
        }
    }

    /** Compact display for a tool call. */
    public record Display(
            String name,
            String argsSummary,
            String resultSummary,
            boolean success) {
    }

    private final Map<String, String> argLabels = new LinkedHashMap<>();

    /** Register a human-readable label for an arg key. */
    public void registerArgLabel(String key, String label) {
        if (key == null) return;
        argLabels.put(key, label == null ? key : label);
    }

    /** Render a tool call as a single-line summary. */
    public String summarizeCall(String toolName, Map<String, Object> args) {
        if (args == null || args.isEmpty()) return toolName + "()";
        StringBuilder sb = new StringBuilder(toolName).append('(');
        boolean first = true;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (!first) sb.append(", ");
            first = false;
            String label = argLabels.getOrDefault(e.getKey(), e.getKey());
            sb.append(label).append('=').append(stringify(e.getValue()));
        }
        sb.append(')');
        return sb.toString();
    }

    /** Render a tool result as a single-line summary. */
    public String summarizeResult(Object result, boolean success) {
        if (result == null) return success ? "ok" : "error";
        String s = stringify(result);
        if (s.length() > 80) s = s.substring(0, 77) + "...";
        return (success ? "ok: " : "error: ") + s;
    }

    private static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return s;
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return value.getClass().getSimpleName();
    }
}
