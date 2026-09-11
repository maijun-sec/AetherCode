package org.aethercode.core.format;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * parse and render GitHub-flavoured Markdown tables. The parser
 * handles the common subset (header row, alignment row, body rows) and
 * the renderer emits fixed-width text with optional ANSI colour codes.
 *
 * <p>Alignment is read from the separator row: {@code :---} left,
 * {@code :---:} centre, {@code ---:} right, {@code ---} default-left.
 */
public final class MarkdownTable {

    public enum Align { LEFT, RIGHT, CENTER }

    public record Cell(String text, Align align) {
        public Cell { text = text == null ? "" : text; align = align == null ? Align.LEFT : align; }
        public static Cell of(String text) { return new Cell(text, Align.LEFT); }
    }

    public record Row(List<Cell> cells) {
        public Row { cells = List.copyOf(cells); }
        public int size() { return cells.size(); }
    }

    public record Table(List<String> headers, List<Align> aligns, List<Row> rows) {
        public Table {
            headers = List.copyOf(headers);
            aligns = List.copyOf(aligns);
            rows = List.copyOf(rows);
        }
        public int cols() { return headers.size(); }
    }

    private static final Pattern PIPE = Pattern.compile("\\|");
    private static final Pattern ALIGN = Pattern.compile("^\\s*(:?)(-+)\\s*(:?)\\s*$");

    private MarkdownTable() {}

    public static Table parse(String md) {
        if (md == null || md.isBlank()) return new Table(List.of(), List.of(), List.of());
        String[] rawLines = md.split("\\r?\\n");
        List<String> lines = new ArrayList<>();
        for (String l : rawLines) {
            String s = l.trim();
            if (s.isEmpty()) continue;
            if (s.startsWith("|")) s = s.substring(1);
            if (s.endsWith("|")) s = s.substring(0, s.length() - 1);
            lines.add(s);
        }
        if (lines.size() < 2) return new Table(List.of(), List.of(), List.of());
        List<String> headerCells = splitRow(lines.get(0));
        List<Align> aligns = parseAlignments(splitRow(lines.get(1)));
        // pad aligns to header length
        while (aligns.size() < headerCells.size()) aligns.add(Align.LEFT);
        List<Row> rows = new ArrayList<>();
        for (int i = 2; i < lines.size(); i++) {
            List<String> cells = splitRow(lines.get(i));
            while (cells.size() < headerCells.size()) cells.add("");
            if (cells.size() > headerCells.size()) cells = cells.subList(0, headerCells.size());
            List<Cell> rowCells = new ArrayList<>();
            for (int c = 0; c < cells.size(); c++) {
                rowCells.add(new Cell(cells.get(c), aligns.get(c)));
            }
            rows.add(new Row(rowCells));
        }
        return new Table(headerCells, aligns, rows);
    }

    private static List<String> splitRow(String row) {
        // Split, but allow escaped pipes \| and trim cells.
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < row.length(); i++) {
            char ch = row.charAt(i);
            if (ch == '\\' && i + 1 < row.length() && row.charAt(i + 1) == '|') {
                cur.append('|'); i++;
            } else if (ch == '|') {
                out.add(cur.toString().trim()); cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString().trim());
        return out;
    }

    private static List<Align> parseAlignments(List<String> cells) {
        List<Align> out = new ArrayList<>();
        for (String c : cells) {
            Matcher m = ALIGN.matcher(c);
            if (!m.matches()) { out.add(Align.LEFT); continue; }
            boolean left  = m.group(1).equals(":");
            boolean right = m.group(3).equals(":");
            if (left && right) out.add(Align.CENTER);
            else if (right)    out.add(Align.RIGHT);
            else                out.add(Align.LEFT);
        }
        return out;
    }

    /** render the table as fixed-width plain text (no colours). */
    public static String render(Table t) {
        return render(t, false, "");
    }

    /** render with optional ANSI dim colour around header. */
    public static String render(Table t, boolean ansi, String dimCode) {
        Objects.requireNonNull(t, "t");
        int n = t.cols();
        if (n == 0) return "";
        int[] widths = new int[n];
        for (int c = 0; c < n; c++) widths[c] = stripAnsi(t.headers().get(c)).length();
        for (Row r : t.rows()) {
            for (int c = 0; c < n && c < r.size(); c++) {
                widths[c] = Math.max(widths[c], stripAnsi(r.cells().get(c).text()).length());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(formatRow(t.headers().stream().map(Cell::of).toList(), widths, ansi ? dimCode : "", ansi ? "\u001b[0m" : ""));
        sb.append(separator(widths, t.aligns()));
        for (Row r : t.rows()) sb.append(formatRow(r.cells(), widths, "", ""));
        return sb.toString();
    }

    private static String formatRow(List<Cell> cells, int[] widths, String openAnsi, String closeAnsi) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < widths.length; c++) {
            String text = c < cells.size() ? cells.get(c).text() : "";
            Align align = c < cells.size() ? cells.get(c).align() : Align.LEFT;
            sb.append(' ').append(openAnsi).append(pad(text, widths[c], align)).append(closeAnsi).append(" |");
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String separator(int[] widths, List<Align> aligns) {
        StringBuilder sb = new StringBuilder("|");
        for (int c = 0; c < widths.length; c++) {
            Align a = c < aligns.size() ? aligns.get(c) : Align.LEFT;
            int w = Math.max(1, widths[c]);
            sb.append(' ');
            // We want the separator cell (without surrounding spaces) to be exactly
            // {@code w} characters wide. Colons replace one or two dashes.
            int dashes = w;
            int leftColons  = 0;
            int rightColons = 0;
            switch (a) {
                case LEFT   -> dashes = w;
                case RIGHT  -> { dashes = w - 1; rightColons = 1; }
                case CENTER -> { dashes = w - 2; leftColons = 1; rightColons = 1; }
            }
            dashes = Math.max(0, dashes);
            sb.append(":".repeat(leftColons))
              .append("-".repeat(dashes))
              .append(":".repeat(rightColons));
            sb.append(' ');
            sb.append('|');
        }
        sb.append('\n');
        return sb.toString();
    }

    private static String pad(String s, int width, Align align) {
        int visible = stripAnsi(s).length();
        if (visible >= width) return s;
        int pad = width - visible;
        return switch (align) {
            case LEFT   -> s + " ".repeat(pad);
            case RIGHT  -> " ".repeat(pad) + s;
            case CENTER -> {
                int left = pad / 2;
                int right = pad - left;
                yield " ".repeat(left) + s + " ".repeat(right);
            }
        };
    }

    private static String stripAnsi(String s) {
        if (s == null) return "";
        return s.replaceAll("\u001b\\[[0-9;]*m", "");
    }
}
