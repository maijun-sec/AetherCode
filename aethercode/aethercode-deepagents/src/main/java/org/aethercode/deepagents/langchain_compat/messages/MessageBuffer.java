package org.aethercode.deepagents.langchain_compat.messages;

import java.util.List;
import java.util.Map;

/**
 * LangChain-compatible message buffer helpers.
 *
 * <p>Java-native port of
 * {@code langchain_core.messages.utils.get_buffer_string}. Renders
 * a list of messages as a human-readable string with role
 * prefixes &mdash; used by summarization middleware to assemble
 * a flat transcript for token estimation and as LLM input.</p>
 */
public final class MessageBuffer {
    private MessageBuffer() {}

    /**
     * Default separator between messages.
     */
    public static final String DEFAULT_SEPARATOR = "\n";

    /**
     * Format a list of messages as a human-readable buffer string.
     *
     * <p>Each message is rendered as {@code "<role>: <content>"}.
     * AI messages with {@code tool_calls} append a
     * {@code "[tool_calls: ...]"} suffix. Tool messages append
     * the tool name in brackets.</p>
     */
    public static String getBufferString(List<?> messages) {
        return getBufferString(messages, DEFAULT_SEPARATOR);
    }

    public static String getBufferString(List<?> messages, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) sb.append(separator);
            sb.append(formatOne(messages.get(i)));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static String formatOne(Object message) {
        if (message == null) return "null";

        // Try to extract via reflection: any object with `role()` + `content()`.
        String role = readString(message, "role");
        Object content = readObject(message, "content");
        String text = flatten(content);

        StringBuilder line = new StringBuilder();
        if (role != null && !role.isEmpty()) {
            line.append(capitalize(role)).append(": ");
        }
        line.append(text);

        // AI messages may carry tool_calls; check for that.
        Object toolCalls = readObject(message, "tool_calls");
        if (toolCalls instanceof List<?> list && !list.isEmpty()) {
            line.append(" [tool_calls: ").append(list).append("]");
        }

        // Tool messages: read `name` if present.
        Object name = readObject(message, "name");
        if (name instanceof String s && !s.isEmpty() && "tool".equalsIgnoreCase(role)) {
            line.append(" [name=").append(s).append("]");
        }
        return line.toString();
    }

    @SuppressWarnings("unchecked")
    private static String flatten(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        if (content instanceof List<?> blocks) {
            StringBuilder sb = new StringBuilder();
            for (Object b : blocks) {
                if (b == null) continue;
                if (b instanceof String s) {
                    sb.append(s);
                } else {
                    // try to read `text` field, then `content` field
                    Object t = readObject(b, "text");
                    if (t instanceof String ts) {
                        sb.append(ts);
                    } else {
                        sb.append(String.valueOf(b));
                    }
                }
            }
            return sb.toString();
        }
        if (content instanceof Map<?, ?> map) {
            Object t = map.get("text");
            if (t instanceof String ts) return ts;
            return String.valueOf(content);
        }
        return String.valueOf(content);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String readString(Object o, String method) {
        Object v = readObject(o, method);
        return v instanceof String s ? s : null;
    }

    private static Object readObject(Object o, String method) {
        if (o == null) return null;
        try {
            return o.getClass().getMethod(method).invoke(o);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * Approximate token count: whitespace-split words * 1.3, with
     * a +4 per-message overhead. Mirrors the heuristic in
     * {@code count_tokens_approximately} for tests.
     */
    public static int approximateTokens(List<?> messages) {
        int total = 0;
        for (Object m : messages) {
            total += 4;
            total += (int) Math.ceil(formatOne(m).split("\\s+").length * 1.3);
        }
        return total;
    }
}
