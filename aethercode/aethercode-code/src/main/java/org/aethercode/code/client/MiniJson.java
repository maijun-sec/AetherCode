package org.aethercode.code.client;

import java.util.List;
import java.util.Map;

/**
 * Minimal JSON serializer used by the remote client.
 *
 * <p>Mirrors the Python {@code deepagents_code.client.remote_client}
 * request/response shape with strings, numbers, booleans, lists, and
 * nested maps.</p>
 */
public final class MiniJson {
    private MiniJson() {}

    /** Serialize a single object. */
    public static String writeObject(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) { sb.append("null"); return; }
        if (value instanceof String s) { writeString(sb, s); return; }
        if (value instanceof Number || value instanceof Boolean) { sb.append(value); return; }
        if (value instanceof Map<?, ?> map) { writeObject(sb, map); return; }
        if (value instanceof List<?> list) { writeArray(sb, list); return; }
        writeString(sb, value.toString());
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            write(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            write(sb, list.get(i));
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
