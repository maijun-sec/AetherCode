package org.aethercode.examples.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny TOML reader used by the better-harness example. Implements
 * just enough TOML to parse the experiment config: tables, key /
 * value pairs, string / integer / float / boolean / array / inline
 * table values, and {@code ${ENV}} interpolation in string values.
 *
 * <p>This is a focused, illustrative parser &mdash; production
 * configs would use a real TOML library.</p>
 */
public final class TinyToml {
    private TinyToml() {}

    /** Parse a TOML document into a nested map. */
    public static Map<String, Object> parse(String text) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        String currentTable = "";
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                String tableName = line.substring(1, line.length() - 1).strip();
                currentTable = tableName;
                current = ensureTable(root, tableName);
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).strip();
            String valueText = line.substring(eq + 1).strip();
            Object value = parseValue(valueText);
            if (key.contains(".")) {
                Map<String, Object> nested = ensureTable(current, key.substring(0, key.lastIndexOf('.')));
                nested.put(key.substring(key.lastIndexOf('.') + 1), value);
            } else {
                current.put(key, value);
            }
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ensureTable(Map<String, Object> parent, String dotted) {
        Map<String, Object> cur = parent;
        for (String part : dotted.split("\\.")) {
            Object existing = cur.get(part);
            if (existing instanceof Map) {
                cur = (Map<String, Object>) existing;
            } else {
                Map<String, Object> next = new LinkedHashMap<>();
                cur.put(part, next);
                cur = next;
            }
        }
        return cur;
    }

    private static Object parseValue(String text) {
        if (text.startsWith("\"") || text.startsWith("'")) {
            return parseString(text);
        }
        if (text.startsWith("[")) {
            return parseArray(text);
        }
        if (text.startsWith("{")) {
            return parseInlineTable(text);
        }
        if (text.equals("true")) return Boolean.TRUE;
        if (text.equals("false")) return Boolean.FALSE;
        try { return Long.parseLong(text); }
        catch (NumberFormatException ignore) { }
        try { return Double.parseDouble(text); }
        catch (NumberFormatException ignore) { }
        return text;
    }

    private static String parseString(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length() - 1) {
                char esc = text.charAt(++i);
                switch (esc) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '\\' -> sb.append('\\');
                    case '"' -> sb.append('"');
                    case '\'' -> sb.append('\'');
                    default -> sb.append(esc);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static List<Object> parseArray(String text) {
        List<Object> out = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') {
                if (depth == 0) {
                    if (current.length() > 0) {
                        out.add(parseValue(current.toString().strip()));
                        current.setLength(0);
                    }
                    return out;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                if (current.length() > 0) {
                    out.add(parseValue(current.toString().strip()));
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        return out;
    }

    private static Map<String, Object> parseInlineTable(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        String body = text.substring(1, text.length() - 1);
        for (String pair : body.split(",")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String key = pair.substring(0, eq).strip().replace("\"", "").replace("'", "");
            String value = pair.substring(eq + 1).strip();
            out.put(key, parseValue(value));
        }
        return out;
    }
}
