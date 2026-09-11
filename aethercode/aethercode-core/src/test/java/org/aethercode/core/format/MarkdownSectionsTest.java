package org.aethercode.core.format;

import java.util.List;
import org.aethercode.core.format.MarkdownSections.Section;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownSectionsTest {

    @Test
    void parse_singleSection() {
        String md = "# Title\n\nbody line 1\nbody line 2\n";
        List<Section> sections = MarkdownSections.parse(md);
        assertEquals(1, sections.size());
        assertEquals("Title", sections.get(0).title());
        assertEquals(1, sections.get(0).level());
        assertTrue(sections.get(0).body().contains("body line 1"));
    }

    @Test
    void parse_multipleLevels() {
        String md = ""
                + "# H1\n"
                + "body of h1\n"
                + "## H2a\n"
                + "body of h2a\n"
                + "## H2b\n"
                + "body of h2b\n"
                + "# H1 again\n"
                + "body\n";
        List<Section> sections = MarkdownSections.parse(md);
        assertEquals(4, sections.size());
        assertEquals(1, sections.get(0).level());
        assertEquals(2, sections.get(1).level());
        assertEquals(2, sections.get(2).level());
        assertEquals(1, sections.get(3).level());
    }

    @Test
    void parse_trailingHashesAreTrimmed() {
        String md = "## Title ##\nbody\n";
        List<Section> sections = MarkdownSections.parse(md);
        assertEquals("Title", sections.get(0).title());
    }

    @Test
    void parse_emptyInput() {
        assertTrue(MarkdownSections.parse("").isEmpty());
        assertTrue(MarkdownSections.parse(null).isEmpty());
    }

    @Test
    void parse_noHeadings() {
        String md = "just text\nmore text\n";
        assertTrue(MarkdownSections.parse(md).isEmpty());
    }

    @Test
    void parse_sectionBodyPreservesMultiline() {
        String md = ""
                + "# Title\n"
                + "line 1\n"
                + "line 2\n"
                + "line 3\n";
        Section s = MarkdownSections.parse(md).get(0);
        assertTrue(s.body().contains("line 1"));
        assertTrue(s.body().contains("line 2"));
        assertTrue(s.body().contains("line 3"));
    }

    @Test
    void parse_h1ThroughH6() {
        String md = "# A\n## B\n### C\n#### D\n##### E\n###### F\n";
        List<Section> sections = MarkdownSections.parse(md);
        assertEquals(6, sections.size());
        assertEquals(1, sections.get(0).level());
        assertEquals(6, sections.get(5).level());
    }

    @Test
    void findByTitle_returnsMatch() {
        String md = "# A\nbody\n# B\nbody\n";
        List<Section> sections = MarkdownSections.parse(md);
        Section found = MarkdownSections.findByTitle(sections, "b");
        assertNotNull(found);
        assertEquals("B", found.title());
    }

    @Test
    void findByTitle_returnsNullForMissing() {
        String md = "# A\nbody\n";
        List<Section> sections = MarkdownSections.parse(md);
        assertNull(MarkdownSections.findByTitle(sections, "Missing"));
        assertNull(MarkdownSections.findByTitle(sections, null));
    }

    @Test
    void topLevel_filtersLevelOne() {
        String md = "# A\n## B\n## C\n# D\n";
        List<Section> sections = MarkdownSections.parse(md);
        List<Section> top = MarkdownSections.topLevel(sections);
        assertEquals(2, top.size());
        assertEquals("A", top.get(0).title());
        assertEquals("D", top.get(1).title());
    }

    @Test
    void parse_sectionWithEmptyBody() {
        String md = "# Empty\n# Next\nbody\n";
        List<Section> sections = MarkdownSections.parse(md);
        assertEquals(2, sections.size());
        assertEquals("", sections.get(0).body());
    }

    @Test
    void parse_offsetsAreNonZero() {
        String md = "# Title\nbody\n";
        Section s = MarkdownSections.parse(md).get(0);
        assertTrue(s.startOffset() >= 0);
        assertTrue(s.endOffset() > s.startOffset());
    }

    @Test
    void isTopLevel_returnsTrueForLevelOne() {
        Section s1 = new Section(1, "x", "", 0, 0);
        Section s2 = new Section(2, "x", "", 0, 0);
        assertTrue(s1.isTopLevel());
        assertEquals(false, s2.isTopLevel());
    }
}
