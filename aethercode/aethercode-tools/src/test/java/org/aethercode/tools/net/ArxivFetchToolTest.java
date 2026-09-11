package org.aethercode.tools.net;

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ArxivFetchTool} -- the {@code arxiv_fetch} tool.
 *
 * <p>The actual HTTP path is delegated to {@link WebFetchTool} (covered
 * by {@code WebFetchToolTest}). Here we focus on id normalization,
 * version stripping, and the tool surface.</p>
 */
class ArxivFetchToolTest {

    /* ----------------------- normalizeId() ----------------------- */

    @Test
    void normalizeIdAcceptsBareId() {
        assertEquals("2512.13564", ArxivFetchTool.normalizeId("2512.13564"));
    }

    @Test
    void normalizeIdAcceptsVersionedId() {
        assertEquals("2512.13564v2", ArxivFetchTool.normalizeId("2512.13564v2"));
    }

    @Test
    void normalizeIdStripsArxivPrefix() {
        assertEquals("2512.13564", ArxivFetchTool.normalizeId("arXiv:2512.13564"));
        assertEquals("2512.13564v2", ArxivFetchTool.normalizeId("ARXIV:2512.13564v2"));
    }

    @Test
    void normalizeIdExtractsFromAbsUrl() {
        assertEquals("2512.13564v2",
                ArxivFetchTool.normalizeId("https://arxiv.org/abs/2512.13564v2"));
        assertEquals("2512.13564",
                ArxivFetchTool.normalizeId("http://arxiv.org/abs/2512.13564"));
    }

    @Test
    void normalizeIdLowercases() {
        assertEquals("2512.13564v2", ArxivFetchTool.normalizeId("2512.13564V2"));
    }

    @Test
    void normalizeIdRejectsNull() {
        assertNull(ArxivFetchTool.normalizeId(null));
    }

    @Test
    void normalizeIdRejectsBlank() {
        assertNull(ArxivFetchTool.normalizeId(""));
        assertNull(ArxivFetchTool.normalizeId("   "));
    }

    @Test
    void normalizeIdRejectsMissingDot() {
        assertNull(ArxivFetchTool.normalizeId("251213564"));
        assertNull(ArxivFetchTool.normalizeId("nodot"));
    }

    @Test
    void normalizeIdRejectsBadNumberPart() {
        // too few digits
        assertNull(ArxivFetchTool.normalizeId("2512.123"));
        // too many digits
        assertNull(ArxivFetchTool.normalizeId("2512.123456"));
        // non-numeric
        assertNull(ArxivFetchTool.normalizeId("2512.abcde"));
    }

    @Test
    void normalizeIdRejectsNonDigitVersion() {
        // 'v' without digits after it
        assertNull(ArxivFetchTool.normalizeId("2512.13564va"));
    }

    /* ----------------------- stripVersion() ----------------------- */

    @Test
    void stripVersionReturnsInputWhenNoVersion() {
        assertEquals("2512.13564", ArxivFetchTool.stripVersion("2512.13564"));
    }

    @Test
    void stripVersionRemovesDigitVersion() {
        assertEquals("2512.13564", ArxivFetchTool.stripVersion("2512.13564v2"));
    }

    @Test
    void stripVersionStripsValidVersionSuffix() {
        // A valid arxiv id is YYMM.NNNNN[vN] where the version is at the
        // tail. The 'v' is a delimiter, not part of the paper number.
        // stripVersion is only called on already-normalized ids, so
        // we only need to assert the well-formed case.
        assertEquals("2512.13564", ArxivFetchTool.stripVersion("2512.13564v3"));
        assertEquals("2512.13564", ArxivFetchTool.stripVersion("2512.13564v12"));
    }

    /* ----------------------- Tool surface ----------------------- */

    @Test
    void toolNameIsArxivFetch() {
        assertThat(ArxivFetchTool.NAME).isEqualTo("arxiv_fetch");
    }

    @Test
    void toolIsReadOnly() {
        // Pure read; the tool never mutates state.
        assertThat(ArxivFetchTool.isReadOnly(Map.of("arxiv_id", "2512.13564"))).isTrue();
        assertThat(ArxivFetchTool.isReadOnly(Map.of())).isTrue();
    }

    @Test
    void emptyArxivIdReturnsError() {
        Tool t = ArxivFetchTool.build();
        var res = t.call(Map.of("arxiv_id", ""), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isTrue();
        assertThat(res.output().toString()).contains("arxiv_id is required");
    }

    @Test
    void missingArxivIdReturnsError() {
        Tool t = ArxivFetchTool.build();
        var res = t.call(Map.of(), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isTrue();
        assertThat(res.output().toString()).contains("arxiv_id is required");
    }

    @Test
    void malformedArxivIdReturnsError() {
        Tool t = ArxivFetchTool.build();
        var res = t.call(Map.of("arxiv_id", "not-an-id"), Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isTrue();
        assertThat(res.output().toString()).contains("invalid arxiv_id");
    }

    @Test
    void invalidFormatReturnsError() {
        Tool t = ArxivFetchTool.build();
        var res = t.call(Map.of("arxiv_id", "2512.13564", "format", "json"),
                Tool.CallContext.of("s")).join();
        assertThat(res.isError()).isTrue();
        assertThat(res.output().toString()).contains("invalid format");
    }

    @Test
    void toolIsRegisteredInStandardTools() {
        boolean found = org.aethercode.tools.StandardTools.all().stream()
                .anyMatch(t -> ArxivFetchTool.NAME.equals(t.name()));
        assertThat(found)
                .as("ArxivFetchTool must be registered in StandardTools.all()")
                .isTrue();
    }

    /* ----------------------- ArxivAtomParser ----------------------- */

    @Test
    void parserExtractsAllFields() {
        String body = """
                <?xml version="1.0" encoding="UTF-8"?>
                <feed>
                  <entry>
                    <id>https://arxiv.org/abs/2512.13564v2</id>
                    <title>Memory in the Age of AI Agents</title>
                    <summary>A comprehensive survey of memory mechanisms.</summary>
                    <author><name>Alice</name></author>
                    <author><name>Bob</name></author>
                    <published>2025-12-15T00:00:00Z</published>
                    <arxiv:primary_category xmlns:arxiv="http://arxiv.org/schemas/atom" term="cs.AI"/>
                    <arxiv:doi xmlns:arxiv="http://arxiv.org/schemas/atom">10.x/y</arxiv:doi>
                  </entry>
                </feed>
                """;
        String out = ArxivAtomParser.parse(body);
        assertNotNull(out);
        assertTrue(out.contains("title: Memory in the Age of AI Agents"), out);
        assertTrue(out.contains("authors: Alice, Bob"), out);
        assertTrue(out.contains("published: 2025-12-15"), out);
        assertTrue(out.contains("primary_category: cs.AI"), out);
        assertTrue(out.contains("doi: 10.x/y"), out);
        assertTrue(out.contains("abstract: A comprehensive survey of memory mechanisms."), out);
        // id should be the entry's <id>, not the feed's
        assertTrue(out.contains("id: https://arxiv.org/abs/2512.13564v2"), out);
    }

    @Test
    void parserDecodesXmlEntities() {
        String body = """
                <feed><entry>
                  <title>Q&amp;A: tests &lt;tag&gt; &quot;hello&quot;</title>
                  <author><name>O&apos;Brien</name></author>
                </entry></feed>
                """;
        String out = ArxivAtomParser.parse(body);
        assertNotNull(out);
        assertTrue(out.contains("title: Q&A: tests <tag> \"hello\""), out);
        assertTrue(out.contains("authors: O'Brien"), out);
    }

    @Test
    void parserReturnsNullOnEmptyBody() {
        assertNull(ArxivAtomParser.parse(""));
        assertNull(ArxivAtomParser.parse(null));
    }

    @Test
    void parserReturnsNullOnUnrecognizedBody() {
        // no entry, no title, no summary, no author -> null
        assertNull(ArxivAtomParser.parse("<feed><opensearch:totalResults>0</opensearch:totalResults></feed>"));
    }

    @Test
    void parserCollapsesWhitespaceInAbstract() {
        String body = """
                <feed><entry>
                  <title>X</title>
                  <summary>Line 1

                  Line 2     with    spaces</summary>
                </entry></feed>
                """;
        String out = ArxivAtomParser.parse(body);
        assertNotNull(out);
        assertTrue(out.contains("abstract: Line 1 Line 2 with spaces"), out);
    }
}
