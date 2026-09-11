package org.aethercode.core.format;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.tool.ToolCatalog;

/**
 * generate a Markdown reference page for a {@link ToolCatalog}.
 * Each tool becomes a section with its description, input schema, and
 * deprecation status. Useful for the TUI's {@code /tools} command
 * and for offline documentation generation.
 */
public final class ToolDocGenerator {

    public record Options(boolean includeSchema, boolean includeDeprecated, String headingLevel) {
        public static Options defaults() {
            return new Options(true, true, "##");
        }
    }

    private ToolDocGenerator() {}

    public static String generate(ToolCatalog catalog) {
        return generate(catalog, Options.defaults());
    }

    public static String generate(ToolCatalog catalog, Options opts) {
        StringBuilder sb = new StringBuilder();
        sb.append(opts.headingLevel).append(" Tools\n\n");
        String toolHeading = deeperHeading(opts.headingLevel, 2);
        var entries = catalog.allEntries();
        if (entries.isEmpty()) {
            sb.append("_No tools registered._\n");
            return sb.toString();
        }
        for (var e : entries) {
            if (e.deprecated() && !opts.includeDeprecated()) continue;
            sb.append(formatTool(e, opts, toolHeading));
        }
        return sb.toString();
    }

    private static String formatTool(ToolCatalog.Entry e, Options opts, String heading) {
        StringBuilder sb = new StringBuilder();
        sb.append(heading).append(" `").append(e.tool().name()).append("`");
        if (e.deprecated()) sb.append(" _[deprecated]_");
        sb.append("\n\n");
        sb.append(e.tool().description()).append("\n\n");
        sb.append("- **Category**: `").append(e.category()).append("`\n");
        if (opts.includeSchema()) {
            sb.append("- **Input schema**:\n\n```json\n");
            sb.append(formatJson(e.tool().inputSchema()));
            sb.append("\n```\n");
        }
        return sb.toString();
    }

    private static String deeperHeading(String base, int levels) {
        int hashes = base.length() + levels;
        if (hashes > 6) hashes = 6;
        return "#".repeat(hashes);
    }

    private static String formatJson(Map<String, Object> schema) {
        // Tiny pretty-printer — avoids the Jackson dependency for a simple case
        StringBuilder sb = new StringBuilder();
        writeJson(sb, schema, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeJson(StringBuilder sb, Object value, int indent) {
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                pad(sb, indent + 1);
                sb.append('"').append(e.getKey()).append("\": ");
                writeJson(sb, e.getValue(), indent + 1);
                if (++i < map.size()) sb.append(',');
                sb.append('\n');
            }
            pad(sb, indent);
            sb.append('}');
        } else if (value instanceof List) {
            sb.append('[');
            List<?> list = (List<?>) value;
            if (list.isEmpty()) {
                sb.append(']');
            } else {
                sb.append('\n');
                for (int i = 0; i < list.size(); i++) {
                    pad(sb, indent + 1);
                    writeJson(sb, list.get(i), indent + 1);
                    if (i < list.size() - 1) sb.append(',');
                    sb.append('\n');
                }
                pad(sb, indent);
                sb.append(']');
            }
        } else if (value instanceof String) {
            sb.append('"').append(((String) value).replace("\"", "\\\"")).append('"');
        } else {
            sb.append(String.valueOf(value));
        }
    }

    private static void pad(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) sb.append("  ");
    }
}
