package org.aethercode.code.tui.widgets;

import java.util.ArrayList;
import java.util.List;

/**
 * Renderers turning a unified diff into one {@code Static} per row.
 *
 * <p>Java port of {@code deepagents_code.tui.widgets.diff}. The Python
 * module is the most syntactically intricate widget in the package: it
 * pulls together a {@code _RowStyle} map keyed over the diff row kind,
 * an end-of-input anchored trailing-block regex, a {@code SequenceMatcher}
 * for word-level emphasis on paired removed/added lines, and the
 * Textual {@code highlight} lexer for syntax coloring.</p>
 *
 * <p>The Java port preserves the public surface ({@link #composeDiffLines},
 * {@link #formatDiffStats}, {@link #highlightSourcePrefixes}) and the
 * constant set. The actual lexer and word-level emphasis need a host
 * TUI; the Java port emits plain {@link WidgetNode.Static} rows with
 * the gutter and marker preserved, and leaves the syntax/word
 * emphasis to the host.</p>
 */
public final class DiffRender {

    private DiffRender() {}

    /** Largest source prefix worth lexing for syntax highlighting. */
    public static final int MAX_HIGHLIGHT_CHARS = 100_000;

    /** Minimum word-level similarity before emphasis is applied. */
    public static final double SIMILARITY_FLOOR = 0.4;

    /** Longest line eligible for word emphasis. */
    public static final int MAX_EMPHASIS_LEN = 400;

    /** Row kind in the diff. */
    public enum RowKind { CONTEXT, ADDED, REMOVED, SEPARATOR, TRUNCATED, NOTE }

    /** One rendered line of a diff. */
    public record Row(RowKind kind, String text, Integer number) {
        public Row(RowKind kind, String text) {
            this(kind, text, null);
        }
    }

    /**
     * Yield a list of {@link WidgetNode.Static} rows for a unified diff.
     *
     * <p>Mirrors the Python {@code compose_diff_lines} generator. The
     * returned list contains one row per line of the diff (after
     * dropping file/hunk headers) and a trailing "more lines" note
     * when rows were clipped to fit {@code maxLines}.</p>
     */
    public static List<WidgetNode> composeDiffLines(String diff, Integer maxLines,
                                                    String path, String before,
                                                    String after, boolean showNumbers) {
        if (diff == null || diff.isEmpty()) {
            return List.of(new WidgetNode.Static("No changes detected",
                    WidgetNode.Role.MUTED, true, false, false));
        }
        List<Row> rows = parseRows(splitDiffLines(diff));
        int total = rows.size();
        if (maxLines != null && rows.size() > maxLines) {
            rows = new ArrayList<>(rows.subList(0, maxLines));
        }
        int hidden = total - rows.size();
        int width = Math.max(2, rows.stream()
                .map(r -> r.number() == null ? 0 : String.valueOf(r.number()).length())
                .max(Integer::compareTo).orElse(0));
        List<WidgetNode> out = new ArrayList<>();
        for (Row row : rows) {
            switch (row.kind()) {
                case SEPARATOR -> out.add(new WidgetNode.Static("─",
                        WidgetNode.Role.PRIMARY, false, true, false));
                case TRUNCATED -> out.add(new WidgetNode.Static("... diff truncated",
                        WidgetNode.Role.MUTED, true, false, false));
                case NOTE -> out.add(new WidgetNode.Static(row.text(),
                        WidgetNode.Role.MUTED, true, false, false));
                default -> {
                    String marker = switch (row.kind()) {
                        case ADDED -> "+";
                        case REMOVED -> "-";
                        default -> " ";
                    };
                    WidgetNode.Role markerRole = switch (row.kind()) {
                        case ADDED -> WidgetNode.Role.SUCCESS;
                        case REMOVED -> WidgetNode.Role.ERROR;
                        default -> WidgetNode.Role.MUTED;
                    };
                    StringBuilder sb = new StringBuilder();
                    if (showNumbers && row.number() != null) {
                        sb.append(String.format("%" + width + "d ", row.number()));
                    }
                    sb.append(marker).append(' ').append(row.text());
                    out.add(new WidgetNode.Static(sb.toString(), markerRole));
                }
            }
        }
        if (hidden > 0) {
            out.add(new WidgetNode.Static("\n... (" + hidden + " more lines)",
                    WidgetNode.Role.MUTED, true, false, false));
        }
        return out;
    }

    /** Format addition/deletion counts as styled {@code +N -M} content. */
    public static WidgetNode formatDiffStats(ToolWidgets.DiffStats stats) {
        if (stats == null) return new WidgetNode.Static("");
        List<WidgetNode> parts = new ArrayList<>();
        if (stats.additions() > 0) {
            parts.add(new WidgetNode.Static("+" + stats.additions(),
                    WidgetNode.Role.SUCCESS));
        }
        if (stats.deletions() > 0) {
            if (!parts.isEmpty()) parts.add(new WidgetNode.Static(" "));
            parts.add(new WidgetNode.Static("-" + stats.deletions(),
                    WidgetNode.Role.ERROR));
        }
        if (parts.isEmpty()) return new WidgetNode.Static("");
        return new WidgetNode.Container(WidgetNode.Layout.HORIZONTAL, parts);
    }

    /**
     * Keep the bounded source prefixes needed to highlight a diff.
     *
     * <p>The Java port is a placeholder: the actual lexer needs a host
     * TUI. The returned strings are the inputs trimmed to the largest
     * needed line numbers per side, matching the Python
     * {@code highlight_source_prefixes} contract.</p>
     */
    public static SourcePrefixes highlightSourcePrefixes(String diff, String before, String after) {
        List<Row> rows = parseRows(splitDiffLines(diff));
        int beforeLine = maxNumber(rows, List.of(RowKind.REMOVED));
        int afterLine = maxNumber(rows, List.of(RowKind.ADDED, RowKind.CONTEXT));
        return new SourcePrefixes(
                prefix(before, beforeLine),
                prefix(after, afterLine));
    }

    public record SourcePrefixes(String before, String after) {}

    private static String prefix(String source, int line) {
        if (source == null || source.isEmpty() || line <= 0) return "";
        if (source.length() > MAX_HIGHLIGHT_CHARS) {
            String head = source.substring(0, MAX_HIGHLIGHT_CHARS + 1);
            String[] lines = head.split("\\R", -1);
            if (lines.length <= line) return "";
            return String.join("\n", java.util.Arrays.copyOf(lines, line)) + "\n";
        }
        String[] lines = source.split("\\R", -1);
        if (lines.length <= line) return source;
        return String.join("\n", java.util.Arrays.copyOf(lines, line)) + "\n";
    }

    private static int maxNumber(List<Row> rows, List<RowKind> kinds) {
        return rows.stream()
                .filter(r -> r.number() != null && kinds.contains(r.kind()))
                .mapToInt(r -> r.number())
                .max()
                .orElse(0);
    }

    /** Parse diff lines into {@link Row}s. */
    public static List<Row> parseRows(List<String> lines) {
        List<Row> out = new ArrayList<>(lines.size());
        for (String raw : lines) {
            if (raw == null || raw.isEmpty()) continue;
            char first = raw.charAt(0);
            String body = raw.length() > 1 ? raw.substring(1) : "";
            switch (first) {
                case '+' -> out.add(new Row(RowKind.ADDED, body, nextNumber(out, RowKind.ADDED, RowKind.CONTEXT)));
                case '-' -> out.add(new Row(RowKind.REMOVED, body, nextNumber(out, RowKind.REMOVED)));
                case '@' -> out.add(new Row(RowKind.SEPARATOR, raw));
                case '\\' -> out.add(new Row(RowKind.NOTE, raw));
                default -> out.add(new Row(RowKind.CONTEXT, raw,
                        nextNumber(out, RowKind.ADDED, RowKind.CONTEXT)));
            }
        }
        return out;
    }

    private static int nextNumber(List<Row> rows, RowKind... kinds) {
        int n = 1;
        for (Row r : rows) {
            if (r.number() != null) {
                for (RowKind k : kinds) {
                    if (r.kind() == k) { n = r.number() + 1; break; }
                }
            }
        }
        return n;
    }

    /** Split a diff string into lines, mirroring the Python {@code split_diff_lines}. */
    public static List<String> splitDiffLines(String diff) {
        if (diff == null || diff.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : diff.split("\n", -1)) {
            if (!line.isEmpty()) out.add(line);
        }
        return out;
    }
}
