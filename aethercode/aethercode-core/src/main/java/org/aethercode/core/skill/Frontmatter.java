package org.aethercode.core.skill;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * minimal YAML-frontmatter parser for SKILL.md / agent.md.
 *
 * <p>Why hand-rolled instead of SnakeYAML / jackson-dataformat-yaml?
 * The Mavis SKILL.md format we read is a tiny subset:
 * <ul>
 *   <li>{@code key: value} on a single line</li>
 *   <li>{@code key: |} block scalar followed by indented lines (the
 *       indented block is concatenated with spaces)</li>
 *   <li>{@code key:} (no value) followed by a 2-space-indented
 *       sub-map (we capture this as a {@code Map<String,String>}
 *       with the locale code as the key, e.g.
 *       {@code descriptions.zh-Hans = "..."})</li>
 * </ul>
 *
 * <p>The rest of the file (after the closing {@code ---}) is
 * treated as the markdown body. A real YAML parser is the
 * obvious follow-up if Mavis starts shipping nested lists or
 * quoted multi-line strings; until then the dependency would
 * outweigh the value.
 */
public final class Frontmatter {

    private Frontmatter() {}

    /** One parsed frontmatter file. {@code fields} is in declaration
     *  order so a {@link #render} round-trips cleanly; {@code body}
     *  is the markdown after the closing {@code ---}. */
    public record Parsed(Map<String, Object> fields, String body) {}

    /** Parse {@code content} as frontmatter + body. Returns
     *  {@code new Parsed(Map.of(), content)} when the file
     *  does not start with {@code ---} on the first line —
     *  i.e. a plain markdown file with no frontmatter. */
    public static Parsed parse(String content) {
        if (content == null || content.isEmpty()) {
            return new Parsed(new LinkedHashMap<>(), content == null ? "" : content);
        }
        // Normalize line endings.
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (!normalized.startsWith("---")) {
            return new Parsed(new LinkedHashMap<>(), normalized);
        }
        // Find the closing fence.
        int idx = normalized.indexOf('\n');
        if (idx < 0) {
            return new Parsed(new LinkedHashMap<>(), normalized);
        }
        // Walk lines until we see a line that is exactly `---` (or `...`).
        // We use `---` only because that's what the Mavis skills use.
        int cursor = idx + 1;
        int end = -1;
        while (cursor < normalized.length()) {
            int next = normalized.indexOf('\n', cursor);
            String line = (next < 0 ? normalized.substring(cursor) : normalized.substring(cursor, next)).trim();
            if ("---".equals(line)) {
                end = cursor;
                break;
            }
            if (next < 0) break;
            cursor = next + 1;
        }
        if (end < 0) {
            // Unterminated frontmatter; treat the whole thing as body.
            return new Parsed(new LinkedHashMap<>(), normalized);
        }
        String header = normalized.substring(idx + 1, end);
        String body = normalized.substring(end);
        // Skip past the closing `---` and the newline that follows it.
        int bodyStart = end + 4; // "---" + \n
        if (bodyStart <= normalized.length() && normalized.charAt(end + 3) == '\n') {
            // already past the newline
        }
        // Trim leading blank line(s) from the body.
        while (bodyStart < normalized.length() && normalized.charAt(bodyStart) == '\n') {
            bodyStart++;
        }
        String realBody = bodyStart < normalized.length() ? normalized.substring(bodyStart) : "";
        return new Parsed(parseHeader(header), realBody);
    }

    /** Look up {@code key} as a String. Returns {@code null} when
     *  missing or the wrong type. */
    public static String string(Map<String, Object> fields, String key) {
        Object v = fields.get(key);
        return v instanceof String s ? s : null;
    }

    /** Look up {@code key} as a localized string map. Returns
     *  the {@code zh-Hans} value, then {@code zh}, then any
     *  other key, then the {@code default} fallback. */
    public static String localized(Map<String, Object> fields, String key, String fallback) {
        Object v = fields.get(key);
        if (v instanceof Map<?, ?> m) {
            Object zh = m.get("zh-Hans");
            if (zh instanceof String s && !s.isEmpty()) return s;
            zh = m.get("zh");
            if (zh instanceof String s && !s.isEmpty()) return s;
            zh = m.get("en");
            if (zh instanceof String s && !s.isEmpty()) return s;
            for (Object value : m.values()) {
                if (value instanceof String s && !s.isEmpty()) return s;
            }
        } else if (v instanceof String s && !s.isEmpty()) {
            return s;
        }
        return fallback;
    }

    // ---- internals -------------------------------------------------

    private static Map<String, Object> parseHeader(String header) {
        Map<String, Object> out = new LinkedHashMap<>();
        String[] lines = header.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String raw = lines[i];
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) { i++; continue; }
            int colon = line.indexOf(':');
            if (colon < 0) { i++; continue; }
            String key = line.substring(0, colon).trim();
            String rest = line.substring(colon + 1).trim();
            if (rest.isEmpty() || "|".equals(rest) || ">".equals(rest) || "|-".equals(rest) || ">-".equals(rest)) {
                // Either a block scalar (`key: |` or `key: >`) or a
                // sub-map (`key:` with indented children). The
                // distinction is the indicator on the key line:
                //   - `|` / `|-` / `>` / `>-` → block scalar
                //   - empty (just `key:`)   → sub-map
                int j = i + 1;
                boolean isBlockScalar = !rest.isEmpty();
                if (isBlockScalar) {
                    // Gather indented lines as a block scalar.
                    if (j < lines.length && (lines[j].startsWith("  ") || lines[j].startsWith("\t"))) {
                        StringBuilder sb = new StringBuilder();
                        boolean folded = rest.startsWith(">");  // `>` folds newlines to spaces
                        while (j < lines.length && (lines[j].startsWith("  ") || lines[j].startsWith("\t") || lines[j].trim().isEmpty())) {
                            if (lines[j].trim().isEmpty()) {
                                if (sb.length() > 0) sb.append('\n');
                                j++;
                                continue;
                            }
                            if (sb.length() > 0) {
                                sb.append(folded ? ' ' : '\n');
                            }
                            // Strip the 2-space indent (or single tab).
                            String s = lines[j];
                            if (s.startsWith("  ")) s = s.substring(2);
                            else if (s.startsWith("\t")) s = s.substring(1);
                            sb.append(s);
                            j++;
                        }
                        out.put(key, sb.toString().trim());
                        i = j;
                        continue;
                    }
                    out.put(key, "");
                    i++;
                } else {
                    // Sub-map. Look at the next line to see if it is
                    // indented. If it is, gather `<subkey>: <value>`
                    // children. If not, the key is just empty.
                    if (j < lines.length) {
                        String next = lines[j];
                        if (next.startsWith("  ") || next.startsWith("\t")) {
                            Map<String, String> sub = new LinkedHashMap<>();
                            while (j < lines.length && (lines[j].startsWith("  ") || lines[j].startsWith("\t"))) {
                                String subRaw = lines[j].trim();
                                if (subRaw.isEmpty()) { j++; continue; }
                                int subColon = subRaw.indexOf(':');
                                if (subColon < 0) { j++; continue; }
                                String subKey = subRaw.substring(0, subColon).trim();
                                String subVal = subRaw.substring(subColon + 1).trim();
                                subVal = stripQuotes(subVal);
                                sub.put(subKey, subVal);
                                j++;
                            }
                            out.put(key, sub);
                            i = j;
                            continue;
                        }
                    }
                    out.put(key, "");
                    i++;
                }
            } else {
                // Strip surrounding quotes.
                rest = stripQuotes(rest);
                out.put(key, rest);
                i++;
            }
        }
        return out;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char a = s.charAt(0), b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }
}
