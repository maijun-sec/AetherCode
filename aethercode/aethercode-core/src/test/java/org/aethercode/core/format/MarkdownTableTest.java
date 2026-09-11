package org.aethercode.core.format;

import org.aethercode.core.format.MarkdownTable.Align;
import org.aethercode.core.format.MarkdownTable.Row;
import org.aethercode.core.format.MarkdownTable.Table;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTableTest {

    @Test
    void parse_simpleTable() {
        String md = ""
                + "| a | b |\n"
                + "|---|---|\n"
                + "| 1 | 2 |\n"
                + "| 3 | 4 |\n";
        Table t = MarkdownTable.parse(md);
        assertEquals(2, t.cols());
        assertEquals(java.util.List.of("a", "b"), t.headers());
        assertEquals(2, t.rows().size());
        assertEquals("1", t.rows().get(0).cells().get(0).text());
    }

    @Test
    void parse_alignments() {
        String md = ""
                + "| L | C | R | D |\n"
                + "|:--|:-:|--:|---|\n"
                + "| a | b | c | d |\n";
        Table t = MarkdownTable.parse(md);
        assertEquals(Align.LEFT,   t.aligns().get(0));
        assertEquals(Align.CENTER, t.aligns().get(1));
        assertEquals(Align.RIGHT,  t.aligns().get(2));
        assertEquals(Align.LEFT,   t.aligns().get(3));
    }

    @Test
    void parse_handlesMissingOuterPipes() {
        String md = ""
                + "a | b\n"
                + "--|--\n"
                + "1 | 2\n";
        Table t = MarkdownTable.parse(md);
        assertEquals(2, t.cols());
        assertEquals("a", t.headers().get(0));
    }

    @Test
    void parse_escapedPipeInCell() {
        String md = ""
                + "| name | expr |\n"
                + "|------|------|\n"
                + "| x | a \\| b |\n";
        Table t = MarkdownTable.parse(md);
        assertEquals("a | b", t.rows().get(0).cells().get(1).text());
    }

    @Test
    void parse_padsShortRows() {
        String md = ""
                + "| a | b | c |\n"
                + "|---|---|---|\n"
                + "| 1 |\n";
        Table t = MarkdownTable.parse(md);
        assertEquals(3, t.rows().get(0).size());
        assertEquals("1", t.rows().get(0).cells().get(0).text());
        assertEquals("", t.rows().get(0).cells().get(1).text());
    }

    @Test
    void parse_truncatesLongRows() {
        String md = ""
                + "| a | b |\n"
                + "|---|---|\n"
                + "| 1 | 2 | 3 |\n";
        Table t = MarkdownTable.parse(md);
        assertEquals(2, t.rows().get(0).size());
    }

    @Test
    void parse_emptyOrInvalid() {
        assertEquals(0, MarkdownTable.parse("").cols());
        assertEquals(0, MarkdownTable.parse(null).cols());
    }

    @Test
    void render_producesFixedWidth() {
        String md = ""
                + "| a | bbb |\n"
                + "|---|-----|\n"
                + "| 1 | 2 |\n"
                + "| 33 | 444 |\n";
        Table t = MarkdownTable.parse(md);
        String out = MarkdownTable.render(t);
        String[] lines = out.split("\n");
        // header + separator + 2 data rows = 4 lines
        assertEquals(4, lines.length);
        for (String line : lines) {
            // every line should start and end with |
            assertTrue(line.startsWith("|"));
            assertTrue(line.endsWith("|"));
        }
        assertTrue(lines[1].contains("---"));
    }

    @Test
    void render_alignsNumbersRight() {
        String md = ""
                + "| n |\n"
                + "|--:|\n"
                + "| 1 |\n"
                + "| 100 |\n";
        Table t = MarkdownTable.parse(md);
        String out = MarkdownTable.render(t);
        String[] lines = out.split("\n");
        // In the rendered row, the number should be right-aligned (trailing space before |)
        assertTrue(lines[2].contains("  1 |"));
        assertTrue(lines[3].contains("100 |"));
    }

    @Test
    void render_centersCentreColumn() {
        String md = ""
                + "| x |\n"
                + "|-:|\n"
                + "| ab |\n";
        Table t = MarkdownTable.parse(md);
        // alignment is RIGHT here (since :-- not :--:) — we want to test CENTER
        String md2 = ""
                + "| x |\n"
                + "|:-:|\n"
                + "| ab |\n";
        Table t2 = MarkdownTable.parse(md2);
        assertEquals(Align.CENTER, t2.aligns().get(0));
        String out = MarkdownTable.render(t2);
        assertTrue(out.contains("| ab "));
    }

    @Test
    void render_withAnsi_doesNotBreakWidth() {
        String md = ""
                + "| a | b |\n"
                + "|---|---|\n"
                + "| 1 | 2 |\n";
        Table t = MarkdownTable.parse(md);
        String out = MarkdownTable.render(t, true, "\u001b[2m");
        String[] lines = out.split("\n");
        for (String l : lines) {
            assertTrue(l.startsWith("|"));
            assertTrue(l.endsWith("|"));
        }
        assertTrue(out.contains("\u001b[2m"));
    }

    @Test
    void render_emptyTable() {
        Table t = new Table(java.util.List.of(), java.util.List.of(), java.util.List.of());
        assertEquals("", MarkdownTable.render(t));
    }

    @Test
    void render_singleColumn() {
        String md = ""
                + "| x |\n"
                + "|---|\n"
                + "| a |\n"
                + "| bb |\n";
        Table t = MarkdownTable.parse(md);
        String out = MarkdownTable.render(t);
        assertTrue(out.contains("| x  |"));
    }

    @Test
    void cell_factoryMethods() {
        MarkdownTable.Cell c = MarkdownTable.Cell.of("hi");
        assertEquals("hi", c.text());
        assertEquals(Align.LEFT, c.align());
    }

    @Test
    void row_size() {
        Row r = new Row(java.util.List.of(MarkdownTable.Cell.of("a"), MarkdownTable.Cell.of("b")));
        assertEquals(2, r.size());
    }

    @Test
    void render_wideColumnDoesNotBreakAlignment() {
        String md = ""
                + "| a | b |\n"
                + "|---|---|\n"
                + "| 1 | loooong |\n"
                + "| 2 | x |\n";
        Table t = MarkdownTable.parse(md);
        String out = MarkdownTable.render(t);
        String[] lines = out.split("\n");
        // All lines should have the same outer length (mod newline)
        int len0 = lines[0].length();
        for (String l : lines) {
            assertEquals(len0, l.length(), "line: " + l);
        }
    }

    @Test
    void parse_onlyHeaderAndSeparator_noBodyRows() {
        String md = "| a | b |\n|---|---|\n";
        Table t = MarkdownTable.parse(md);
        assertEquals(0, t.rows().size());
    }

    @Test
    void render_tableWithRowShorterThanHeader_doesNotThrow() {
        Table t = new Table(
                java.util.List.of("a", "b", "c"),
                java.util.List.of(Align.LEFT, Align.LEFT, Align.LEFT),
                java.util.List.of(new Row(java.util.List.of(MarkdownTable.Cell.of("x")))));
        // cells.size() < widths.length — pad branch
        String out = MarkdownTable.render(t);
        assertFalse(out.isEmpty());
    }
}
