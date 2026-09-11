package org.aethercode.examples.deepresearch;

import java.util.List;
import java.util.Map;

/**
 * Utility functions for displaying messages and prompts.
 *
 * <p>Java port of {@code deepagents-main/examples/deep_research/utils.py}.
 * The Python port uses {@code rich} panels for in-Jupyter rendering;
 * the Java port renders to plain text panels and also supports a
 * Rich-style bold/italic marker escape for the terminal. The
 * {@link #formatMessageContent(Object, List)} helper is the workhorse
 * used by {@link #formatMessages(List, java.util.function.BiConsumer)}
 * to convert a message object to its display string.</p>
 */
public final class ResearchUtils {
    private ResearchUtils() {}

    /**
     * Format a single message object's content for display.
     *
     * <p>Mirrors the Python port's {@code format_message_content}:</p>
     * <ul>
     *   <li>If content is a string, return it verbatim.</li>
     *   <li>If content is a list of blocks, render each text and tool
     *       call block.</li>
     *   <li>If the message exposes {@code tool_calls} (OpenAI-style),
     *       render them as tool-call lines.</li>
     * </ul>
     */
    public static String formatMessageContent(Object content, List<Map<String, Object>> toolCalls) {
        StringBuilder out = new StringBuilder();
        boolean toolCallsProcessed = false;
        if (content instanceof String s) {
            out.append(s);
        } else if (content instanceof List<?> blocks) {
            for (Object raw : blocks) {
                if (!(raw instanceof Map<?, ?> block)) continue;
                Object type = block.get("type");
                if ("text".equals(type)) {
                    Object text = block.get("text");
                    if (text != null) out.append(text);
                } else if ("tool_use".equals(type)) {
                    out.append("\n🔧 Tool Call: ").append(block.get("name"));
                    Object input = block.get("input");
                    out.append("\n   Args: ").append(MiniJsonDumps.dump(input, 2));
                    Object id = block.get("id");
                    out.append("\n   ID: ").append(id == null ? "N/A" : id);
                    toolCallsProcessed = true;
                }
            }
        } else if (content != null) {
            out.append(content);
        }
        if (!toolCallsProcessed && toolCalls != null) {
            for (Map<String, Object> tc : toolCalls) {
                out.append("\n🔧 Tool Call: ").append(tc.get("name"));
                out.append("\n   Args: ").append(MiniJsonDumps.dump(tc.get("args"), 2));
                out.append("\n   ID: ").append(tc.get("id"));
            }
        }
        return out.toString();
    }

    /**
     * Format and print a list of messages.
     *
     * <p>Each message is rendered as a {@code [Human | Assistant | Tool | Other]} panel.
     * The {@code sink} is a 2-arg consumer (heading, body) that the
     * caller can use to drive a printer (e.g. {@code System.out::println}
     * or a Rich renderer).</p>
     */
    public static void formatMessages(List<Map<String, Object>> messages,
                                      java.util.function.BiConsumer<String, String> sink) {
        for (Map<String, Object> m : messages) {
            String type = stringOr(m.get("type"), "Other").replace("Message", "");
            Object content = m.get("content");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) m.get("tool_calls");
            String body = formatMessageContent(content, toolCalls);
            String heading = switch (type) {
                case "Human" -> "🧑 Human";
                case "Ai" -> "🤖 Assistant";
                case "Tool" -> "🔧 Tool Output";
                default -> "📝 " + type;
            };
            sink.accept(heading, body);
        }
    }

    /**
     * Alias for {@link #formatMessages(List, java.util.function.BiConsumer)}
     * for backward compatibility.
     */
    public static void formatMessage(List<Map<String, Object>> messages,
                                     java.util.function.BiConsumer<String, String> sink) {
        formatMessages(messages, sink);
    }

    private static String stringOr(Object o, String fallback) {
        return o == null ? fallback : o.toString();
    }

    /**
     * Tiny indentation-aware JSON dumper used to format tool-call
     * arguments for display. The output mirrors the structure of
     * {@code json.dumps(value, indent=2)} closely enough for the
     * example.
     */
    static final class MiniJsonDumps {
        static String dump(Object value, int indent) {
            StringBuilder sb = new StringBuilder();
            write(sb, value, indent, 0);
            return sb.toString();
        }

        private static void write(StringBuilder sb, Object value, int indent, int depth) {
            String pad = repeat("  ", depth);
            if (value == null || value instanceof Boolean || value instanceof Number || value instanceof String) {
                sb.append(value);
                return;
            }
            if (value instanceof Map<?, ?> map) {
                if (map.isEmpty()) { sb.append("{}"); return; }
                sb.append("{\n");
                int i = 0;
                int size = map.size();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    sb.append(pad).append("  \"").append(e.getKey()).append("\": ");
                    write(sb, e.getValue(), indent, depth + 1);
                    if (++i < size) sb.append(",");
                    sb.append("\n");
                }
                sb.append(pad).append("}");
                return;
            }
            if (value instanceof List<?> list) {
                if (list.isEmpty()) { sb.append("[]"); return; }
                sb.append("[\n");
                for (int i = 0; i < list.size(); i++) {
                    sb.append(pad).append("  ");
                    write(sb, list.get(i), indent, depth + 1);
                    if (i < list.size() - 1) sb.append(",");
                    sb.append("\n");
                }
                sb.append(pad).append("]");
                return;
            }
            sb.append("\"").append(value).append("\"");
        }

        private static String repeat(String s, int times) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < times; i++) out.append(s);
            return out.toString();
        }
    }
}
