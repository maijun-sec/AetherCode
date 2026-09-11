package org.aethercode.examples.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny, dependency-free JSON read / write helper used by the
 * example code. Mirrors the surface the Python port uses through
 * {@code json.dumps} / {@code json.loads} but does not depend on
 * Jackson. Used by the better-harness, llm-wiki, and
 * async-subagent-server examples for small payloads.
 */
public final class MiniJson {
    private MiniJson() {}

    /** Serialize an object to JSON. */
    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value, int depth) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value);
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Map<?, ?> m) {
            writeObject(sb, m, depth);
        } else if (value instanceof List<?> l) {
            writeArray(sb, l, depth);
        } else if (value.getClass().isArray()) {
            // Treat arrays as JSON arrays.
            writeArray(sb, java.util.Arrays.asList((Object[]) value), depth);
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> m, int depth) {
        if (m.isEmpty()) { sb.append("{}"); return; }
        sb.append("{\n");
        int i = 0;
        int n = m.size();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            indent(sb, depth + 1);
            writeString(sb, e.getKey().toString());
            sb.append(": ");
            write(sb, e.getValue(), depth + 1);
            if (++i < n) sb.append(",");
            sb.append("\n");
        }
        indent(sb, depth);
        sb.append("}");
    }

    private static void writeArray(StringBuilder sb, List<?> list, int depth) {
        if (list.isEmpty()) { sb.append("[]"); return; }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            indent(sb, depth + 1);
            write(sb, list.get(i), depth + 1);
            if (i < list.size() - 1) sb.append(",");
            sb.append("\n");
        }
        indent(sb, depth);
        sb.append("]");
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
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append("  ");
    }

    // -----------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------

    /** Parse a JSON object. */
    public static Map<String, Object> parseObject(String json) {
        Parser p = new Parser(json);
        p.skipWs();
        Object value = p.parseValue();
        if (!(value instanceof Map)) return Map.of();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }

    /** Parse a JSON value into a Java object. */
    public static Object parse(String json) {
        Parser p = new Parser(json);
        p.skipWs();
        return p.parseValue();
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
            this.pos = 0;
        }

        void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++;
        }

        Object parseValue() {
            skipWs();
            if (pos >= src.length()) return null;
            char c = src.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') { match("null"); return null; }
            return parseNumber();
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') { pos++; return out; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object value = parseValue();
                out.put(key, value);
                skipWs();
                if (peek() == ',') { pos++; continue; }
                if (peek() == '}') { pos++; return out; }
                throw new IllegalStateException("expected ',' or '}' at " + pos);
            }
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> out = new ArrayList<>();
            skipWs();
            if (peek() == ']') { pos++; return out; }
            while (true) {
                out.add(parseValue());
                skipWs();
                if (peek() == ',') { pos++; continue; }
                if (peek() == ']') { pos++; return out; }
                throw new IllegalStateException("expected ',' or ']' at " + pos);
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\' && pos < src.length()) {
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            String hex = src.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalStateException("unterminated string");
        }

        Boolean parseBoolean() {
            if (src.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new IllegalStateException("expected boolean at " + pos);
        }

        Object parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < src.length() && "0123456789.eE+-".indexOf(src.charAt(pos)) >= 0) pos++;
            String token = src.substring(start, pos);
            if (token.contains(".") || token.contains("e") || token.contains("E")) {
                return Double.parseDouble(token);
            }
            try { return Long.parseLong(token); }
            catch (NumberFormatException exc) { return Double.parseDouble(token); }
        }

        char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        void expect(char c) {
            if (peek() != c) {
                throw new IllegalStateException("expected '" + c + "' at " + pos);
            }
            pos++;
        }

        void match(String token) {
            if (!src.startsWith(token, pos)) {
                throw new IllegalStateException("expected '" + token + "' at " + pos);
            }
            pos += token.length();
        }
    }
}
