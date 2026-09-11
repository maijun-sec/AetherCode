package org.aethercode.talon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny TOML parser used by the Talon config loader.
 *
 * <p>Java-native port of the subset of {@code tomllib} the Talon runtime
 * actually uses. The parser understands:</p>
 * <ul>
 *   <li>{@code [section]} headers (one level only — no nested tables).</li>
 *   <li>Keys with string, integer, float, boolean, and string-array values.</li>
 *   <li>Quoted strings with backslash escapes.</li>
 *   <li>Inline {@code #} comments.</li>
 *   <li>Multiline bare and quoted strings are not supported (Talon does
 *       not produce them).</li>
 * </ul>
 *
 * <p>Keys that match the {@code [a-zA-Z0-9_-]+} pattern are returned as
 * a {@code Map<String, Object>}; everything else is left as a raw
 * {@code String} the caller can recover with {@code (String) ...}.</p>
 */
public final class TomlUtils {

    private TomlUtils() {}

    /** Parse a TOML document into a flat map of {@code section -> keys}. */
    public static Map<String, Object> parse(String content) {
        java.util.Map<String, Object> root = new LinkedHashMap<>();
        java.util.Map<String, Object> current = root;
        java.util.List<String> lines = splitLines(content);
        for (String raw : lines) {
            String line = stripComment(raw).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                String section = line.substring(1, line.length() - 1).strip();
                Object existing = root.get(section);
                if (existing instanceof Map<?, ?> rawMap) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> asMap = (Map<String, Object>) rawMap;
                    current = asMap;
                } else {
                    java.util.Map<String, Object> newMap = new LinkedHashMap<>();
                    root.put(section, newMap);
                    current = newMap;
                }
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            current.put(key, parseValue(value));
        }
        return root;
    }

    // -----------------------------------------------------------------------
    // Value parsing
    // -----------------------------------------------------------------------

    private static Object parseValue(String raw) {
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            return unescape(raw.substring(1, raw.length() - 1));
        }
        if (raw.startsWith("[") && raw.endsWith("]")) {
            return parseArray(raw.substring(1, raw.length() - 1));
        }
        if (raw.equals("true")) {
            return Boolean.TRUE;
        }
        if (raw.equals("false")) {
            return Boolean.FALSE;
        }
        if (raw.matches("-?\\d+")) {
            return Long.parseLong(raw);
        }
        if (raw.matches("-?\\d+\\.\\d+")) {
            return Double.parseDouble(raw);
        }
        return raw;
    }

    private static List<Object> parseArray(String body) {
        java.util.List<Object> out = new ArrayList<>();
        if (body.strip().isEmpty()) {
            return out;
        }
        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < body.length()) {
                    current.append(c).append(body.charAt(i + 1));
                    i++;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                current.append(c);
                continue;
            }
            if (c == '"') {
                inString = true;
                current.append(c);
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(parseValue(current.toString().strip()));
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        if (current.length() > 0) {
            out.add(parseValue(current.toString().strip()));
        }
        return out;
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    default -> out.append(c).append(next);
                }
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String stripComment(String line) {
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString && c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static List<String> splitLines(String content) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        return List.of(normalized.split("\n"));
    }
}
