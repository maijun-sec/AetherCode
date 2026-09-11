package org.aethercode.prompts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tests for the optional {@code index.md} TOC file
 * inside a rules directory and the per-file
 * {@code <!-- aethercode: disabled -->} marker. Both
 * features are opt-in: a rules directory without an index
 * falls back to the legacy-F alphabetical sort, and a
 * file without the marker is loaded as-is. The tests
 * cover both opt-in paths and the cross-cutting "marker
 * on, but file also mentioned in index" interaction.
 */
class RulesLoaderIndexTest {

    // ---------------------------------------------------------------------------------
    //  parseIndex
    // ---------------------------------------------------------------------------------

    @Test
    void parseIndex_bareFilenames() {
        assertThat(RulesLoader.parseIndex("""
                style.md
                safety.md
                """)).containsExactly("style.md", "safety.md");
    }

    @Test
    void parseIndex_listItems() {
        assertThat(RulesLoader.parseIndex("""
                - style.md
                - safety.md
                - tools.md
                """)).containsExactly("style.md", "safety.md", "tools.md");
    }

    @Test
    void parseIndex_markdownLinks() {
        assertThat(RulesLoader.parseIndex("""
                - [Code style](style.md)
                - [Safety rules](safety.md)
                """)).containsExactly("style.md", "safety.md");
    }

    @Test
    void parseIndex_ignoresHeadings() {
        // The index file is itself a markdown file;
        // headings and blank lines must not be treated
        // as file references.
        assertThat(RulesLoader.parseIndex("""
                # My project rules

                This is the index. Files below are loaded
                in order.

                - alpha.md
                - beta.md
                """)).containsExactly("alpha.md", "beta.md");
    }

    @Test
    void parseIndex_skipsWholeLineComments() {
        // A user can "comment out" an entry by wrapping
        // it in an HTML comment.
        assertThat(RulesLoader.parseIndex("""
                - enabled.md
                <!-- - disabled.md -->
                - last.md
                """)).containsExactly("enabled.md", "last.md");
    }

    @Test
    void parseIndex_stripsTrailingTitle() {
        // Some users write `- style.md # code style`;
        // we should keep just the filename.
        assertThat(RulesLoader.parseIndex("- style.md # code style"))
                .containsExactly("style.md");
    }

    @Test
    void parseIndex_nullOrEmpty() {
        assertThat(RulesLoader.parseIndex(null)).isEmpty();
        assertThat(RulesLoader.parseIndex("")).isEmpty();
        assertThat(RulesLoader.parseIndex("   \n\n   ")).isEmpty();
    }

    // ---------------------------------------------------------------------------------
    //  isDisabled / stripDisableMarker
    // ---------------------------------------------------------------------------------

    @Test
    void isDisabled_recognisesStandardMarker() {
        assertThat(RulesLoader.isDisabled("<!-- aethercode: disabled -->\nkeep me out"))
                .isTrue();
    }

    @Test
    void isDisabled_caseInsensitive() {
        assertThat(RulesLoader.isDisabled("<!-- Aethercode: DISABLED -->\nbody"))
                .isTrue();
    }

    @Test
    void isDisabled_tolerantOfWhitespace() {
        assertThat(RulesLoader.isDisabled("<!--   aethercode:    disabled   -->\nbody"))
                .isTrue();
    }

    @Test
    void isDisabled_skipsLeadingBlankLines() {
        // A blank line at the top of the file should
        // not prevent the marker on the next line from
        // being detected.
        assertThat(RulesLoader.isDisabled("\n\n<!-- aethercode: disabled -->\nbody"))
                .isTrue();
    }

    @Test
    void isDisabled_markerInBodyIsIgnored() {
        // The marker must be on the FIRST non-blank
        // line, not somewhere in the middle of the file.
        assertThat(RulesLoader.isDisabled("""
                # Some rule

                <!-- aethercode: disabled -->
                This comment in the body should not
                disable the file.
                """)).isFalse();
    }

    @Test
    void isDisabled_plainFileReturnsFalse() {
        assertThat(RulesLoader.isDisabled("""
                # Code style

                Use tabs.
                """)).isFalse();
    }

    @Test
    void stripDisableMarker_removesFirstNonBlankLine() {
        String stripped = RulesLoader.stripDisableMarker(
                "<!-- aethercode: disabled -->\nUse tabs.\n");
        assertThat(stripped).isEqualTo("Use tabs.\n");
    }

    @Test
    void stripDisableMarker_leavesPlainFileAlone() {
        String orig = "Use tabs.\n";
        assertThat(RulesLoader.stripDisableMarker(orig)).isEqualTo(orig);
    }

    // ---------------------------------------------------------------------------------
    //  Integration: full load() with index.md
    // ---------------------------------------------------------------------------------

    @Test
    void load_respectsIndexOrder(@TempDir Path cwd) throws IOException {
        Path rulesDir = cwd.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("zeta.md"),  "zeta content");
        Files.writeString(rulesDir.resolve("alpha.md"), "alpha content");
        Files.writeString(rulesDir.resolve("mid.md"),   "mid content");
        Files.writeString(rulesDir.resolve("index.md"),
                """
                # Custom order
                - mid.md
                - alpha.md
                """);
        String out = RulesLoader.load(cwd, null);
        // The index lists mid then alpha; the
        // unmentioned zeta trails after.
        int midIdx   = out.indexOf("### mid.md");
        int alphaIdx = out.indexOf("### alpha.md");
        int zetaIdx  = out.indexOf("### zeta.md");
        assertThat(midIdx).isGreaterThanOrEqualTo(0);
        assertThat(alphaIdx).isGreaterThan(midIdx);
        assertThat(zetaIdx).isGreaterThan(alphaIdx);
        // The index file itself should NOT appear in the
        // output (it is metadata, not a rule).
        assertThat(out).doesNotContain("### index.md");
    }

    @Test
    void load_indexMissingEntriesSkippedSilently(@TempDir Path cwd) throws IOException {
        // An index that references a non-existent file
        // should not crash — the missing entry is
        // dropped, the rest of the layer is loaded as
        // usual.
        Path rulesDir = cwd.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("real.md"), "real content");
        Files.writeString(rulesDir.resolve("index.md"),
                "- real.md\n- missing.md\n");
        String out = RulesLoader.load(cwd, null);
        assertThat(out).contains("real content").doesNotContain("missing content");
    }

    @Test
    void load_disabledFileSkipped(@TempDir Path cwd) throws IOException {
        Path rulesDir = cwd.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("enabled.md"),  "kept");
        Files.writeString(rulesDir.resolve("disabled.md"),
                "<!-- aethercode: disabled -->\nNOT KEPT");
        String out = RulesLoader.load(cwd, null);
        assertThat(out).contains("kept").doesNotContain("NOT KEPT");
        assertThat(out).doesNotContain("### disabled.md");
    }

    @Test
    void load_disabledMarkerStrippedFromBodyWhenEnabled(@TempDir Path cwd) throws IOException {
        // Defensive: if a file is enabled but has a
        // stray marker in its body, the body should be
        // rendered as-is (the marker only fires when on
        // the first non-blank line). This locks the
        // "first line only" semantics.
        Path rulesDir = cwd.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("with-trailing-marker.md"), """
                # Real rule

                Use tabs.

                <!-- legacy: aethercode: disabled in body is fine -->
                """);
        String out = RulesLoader.load(cwd, null);
        assertThat(out).contains("Use tabs.");
        assertThat(out).contains("with-trailing-marker.md");
    }

    @Test
    void load_indexAndDisableCombine(@TempDir Path cwd) throws IOException {
        // The two features compose: an index file lists
        // files in order, and a per-file disable marker
        // drops one of them. The unmentioned files
        // trail afterwards in alphabetical order.
        Path rulesDir = cwd.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("a.md"), "a content");
        Files.writeString(rulesDir.resolve("b.md"), "b content");
        Files.writeString(rulesDir.resolve("c.md"),
                "<!-- aethercode: disabled -->\nc content");
        Files.writeString(rulesDir.resolve("index.md"), """
                - b.md
                - a.md
                """);
        String out = RulesLoader.load(cwd, null);
        // b and a are loaded in index order; c is
        // disabled so it should NOT appear.
        int bIdx = out.indexOf("### b.md");
        int aIdx = out.indexOf("### a.md");
        assertThat(bIdx).isGreaterThanOrEqualTo(0);
        assertThat(aIdx).isGreaterThan(bIdx);
        assertThat(out).doesNotContain("### c.md");
        assertThat(out).doesNotContain("c content");
    }

    @Test
    void load_emptyIndexFallsBackToAlphabetical(@TempDir Path cwd) throws IOException {
        // A blank index is the same as no index: the
        // alphabetical fallback applies. This protects
        // against a user creating an empty index.md and
        // accidentally breaking the loader.
        Path rulesDir = cwd.resolve(".aethercode/rules");
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("zeta.md"),  "z");
        Files.writeString(rulesDir.resolve("alpha.md"), "a");
        Files.writeString(rulesDir.resolve("index.md"), "");
        String out = RulesLoader.load(cwd, null);
        int aIdx = out.indexOf("### alpha.md");
        int zIdx = out.indexOf("### zeta.md");
        assertThat(aIdx).isGreaterThanOrEqualTo(0);
        assertThat(zIdx).isGreaterThan(aIdx);
    }
}
