package org.aethercode.prompts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * tests for {@link RulesLoader}.
 *
 * <p>The tests cover both the {@code load(Path, Path)} entry point (which touches
 * the real file system through {@link TempDir}) and the {@code renderForTest}
 * helper (which is purely in-memory and lets us cover edge cases like truncation
 * without staging 32 KB+ of files on disk).
 */
class RulesLoaderTest {

    // ---------------------------------------------------------------------------------
    //  Empty / missing inputs
    // ---------------------------------------------------------------------------------

    @Test
    void load_returnsEmptyWhenNoDirsExist(@TempDir Path cwd) {
        String out = RulesLoader.load(cwd, cwd);
        assertThat(out).isEmpty();
    }

    @Test
    void load_returnsEmptyWhenDirsAreEmpty(@TempDir Path cwd) throws IOException {
        Files.createDirectories(cwd.resolve(RulesLoader.PROJECT_RULES_DIR));
        Files.createDirectories(cwd.resolve(RulesLoader.GLOBAL_RULES_DIR));
        assertThat(RulesLoader.load(cwd, cwd)).isEmpty();
    }

    @Test
    void load_nullCwd_skipsProjectLayer(@TempDir Path home) throws IOException {
        Path rulesDir = home.resolve(RulesLoader.GLOBAL_RULES_DIR);
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("style.md"), "be concise");
        String out = RulesLoader.load(null, home);
        assertThat(out).contains("# Global rules").contains("style.md").contains("be concise");
        assertThat(out).doesNotContain("# Project rules");
    }

    @Test
    void load_nullHome_skipsGlobalLayer(@TempDir Path cwd) throws IOException {
        Path rulesDir = cwd.resolve(RulesLoader.PROJECT_RULES_DIR);
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("notes.md"), "use tabs");
        String out = RulesLoader.load(cwd, null);
        assertThat(out).contains("# Project rules").contains("use tabs");
        assertThat(out).doesNotContain("# Global rules");
    }

    @Test
    void load_bothNull_returnsEmpty() {
        assertThat(RulesLoader.load(null, null)).isEmpty();
    }

    // ---------------------------------------------------------------------------------
    //  Single + multiple files
    // ---------------------------------------------------------------------------------

    @Test
    void load_singleFile_isWrappedInHeader(@TempDir Path cwd) throws IOException {
        Path rulesDir = cwd.resolve(RulesLoader.PROJECT_RULES_DIR);
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("a.md"), "rule one");
        String out = RulesLoader.load(cwd, null);
        assertThat(out).startsWith("# Project rules").contains("### a.md").contains("rule one");
    }

    @Test
    void load_multipleFiles_areSortedByName(@TempDir Path cwd) throws IOException {
        Path rulesDir = cwd.resolve(RulesLoader.PROJECT_RULES_DIR);
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("zeta.md"),  "z content");
        Files.writeString(rulesDir.resolve("alpha.md"), "a content");
        Files.writeString(rulesDir.resolve("mid.md"),   "m content");
        String out = RulesLoader.load(cwd, null);
        int aIdx = out.indexOf("### alpha.md");
        int mIdx = out.indexOf("### mid.md");
        int zIdx = out.indexOf("### zeta.md");
        assertThat(aIdx).isGreaterThanOrEqualTo(0);
        assertThat(mIdx).isGreaterThan(aIdx);
        assertThat(zIdx).isGreaterThan(mIdx);
    }

    @Test
    void load_projectAndGlobalAreBothRenderedInOrder(@TempDir Path cwd) throws IOException {
        Path project = cwd.resolve(RulesLoader.PROJECT_RULES_DIR);
        Path home    = Files.createTempDirectory("fakehome");
        Path global  = home.resolve(RulesLoader.GLOBAL_RULES_DIR);
        Files.createDirectories(project);
        Files.createDirectories(global);
        Files.writeString(project.resolve("p.md"), "project rule");
        Files.writeString(global.resolve("g.md"),  "global rule");

        String out = RulesLoader.load(cwd, home);
        int pHeader = out.indexOf("# Project rules");
        int gHeader = out.indexOf("# Global rules");
        assertThat(pHeader).isGreaterThanOrEqualTo(0);
        assertThat(gHeader).isGreaterThan(pHeader);
        assertThat(out).contains("project rule").contains("global rule");
    }

    @Test
    void load_filtersByExtension(@TempDir Path cwd) throws IOException {
        Path rulesDir = cwd.resolve(RulesLoader.PROJECT_RULES_DIR);
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("real.md"), "yes");
        Files.writeString(rulesDir.resolve("README"), "ignored no extension");
        Files.writeString(rulesDir.resolve("image.png"), "ignored png");
        Files.writeString(rulesDir.resolve("notes.txt"), "yes txt");
        Files.writeString(rulesDir.resolve("doc.markdown"), "yes markdown ext");
        String out = RulesLoader.load(cwd, null);
        assertThat(out).contains("real.md").contains("notes.txt").contains("doc.markdown");
        assertThat(out).doesNotContain("README").doesNotContain("image.png");
    }

    @Test
    void load_skipsEmptyFiles(@TempDir Path cwd) throws IOException {
        Path rulesDir = cwd.resolve(RulesLoader.PROJECT_RULES_DIR);
        Files.createDirectories(rulesDir);
        Files.writeString(rulesDir.resolve("blank.md"), "   \n\n   ");
        Files.writeString(rulesDir.resolve("real.md"),  "kept");
        String out = RulesLoader.load(cwd, null);
        assertThat(out).contains("real.md").doesNotContain("blank.md");
    }

    @Test
    void load_skipsSubdirectories(@TempDir Path cwd) throws IOException {
        Path rulesDir = cwd.resolve(RulesLoader.PROJECT_RULES_DIR);
        Files.createDirectories(rulesDir.resolve("nested.md")); // a directory, not a file
        Files.writeString(rulesDir.resolve("real.md"), "kept");
        String out = RulesLoader.load(cwd, null);
        assertThat(out).contains("real.md").doesNotContain("nested.md");
    }

    // ---------------------------------------------------------------------------------
    //  Truncation
    // ---------------------------------------------------------------------------------

    @Test
    void renderForTest_truncatesAtCap() {
        // 1 KB each x 40 = 40 KB > 32 KB cap
        String chunk = "x".repeat(1024);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 40; i++) big.append(chunk);
        String out = RulesLoader.renderForTest(
                List.of("big.md"),
                List.of(big.toString()),
                List.of(),
                List.of());
        assertThat(out.length()).isLessThanOrEqualTo(RulesLoader.MAX_RULES_CHARS);
        assertThat(out).contains(RulesLoader.TRUNCATION_MARKER.trim());
    }

    @Test
    void renderForTest_doesNotTruncateWhenUnderCap() {
        String out = RulesLoader.renderForTest(
                List.of("small.md"),
                List.of("small content"),
                List.of(),
                List.of());
        assertThat(out).doesNotContain("truncated");
        assertThat(out).contains("small content");
    }

    // ---------------------------------------------------------------------------------
    //  renderForTest: argument validation
    // ---------------------------------------------------------------------------------

    @Test
    void renderForTest_mismatchedLengths_throws() {
        assertThatThrownBy(() -> RulesLoader.renderForTest(
                List.of("a.md", "b.md"),
                List.of("only one content"),
                List.of(),
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same length");
    }

    @Test
    void renderForTest_skipsEmptyContent() {
        String out = RulesLoader.renderForTest(
                List.of("a.md", "blank.md", "b.md"),
                List.of("aaa", "   \n   ", "bbb"),
                List.of(),
                List.of());
        assertThat(out).contains("a.md").contains("b.md").doesNotContain("blank.md");
    }

    @Test
    void renderForTest_layersBothProjectAndGlobal() {
        String out = RulesLoader.renderForTest(
                List.of("p.md"),
                List.of("p-body"),
                List.of("g.md"),
                List.of("g-body"));
        int ph = out.indexOf("# Project rules");
        int gh = out.indexOf("# Global rules");
        assertThat(ph).isGreaterThanOrEqualTo(0);
        assertThat(gh).isGreaterThan(ph);
        assertThat(out).contains("p-body").contains("g-body");
    }

    @Test
    void renderForTest_emptyBoth_returnsEmpty() {
        String out = RulesLoader.renderForTest(List.of(), List.of(), List.of(), List.of());
        assertThat(out).isEmpty();
    }

    // ---------------------------------------------------------------------------------
    // lastLoadedFileNames() — the thread-local
    //  accumulator that lets the TUI's /prompt command
    //  show "which rule files contributed?".
    // ---------------------------------------------------------------------------------

    @Test
    void lastLoadedFileNames_isEmptyBeforeAnyLoad(@TempDir Path cwd) {
        // Clear the accumulator (a previous test on this
        // thread may have loaded rules). The test is
        // thread-safe because the accumulator is
        // ThreadLocal.
        RulesLoader.load(cwd, cwd); // empty load, clears the list
        // After an empty load the list must be empty,
        // not stale from a prior test.
        assertThat(RulesLoader.lastLoadedFileNames()).isEmpty();
    }

    @Test
    void lastLoadedFileNames_returnsAbsolutePathsInDocumentOrder(@TempDir Path cwd) throws IOException {
        Path project = cwd.resolve("project");
        Path home    = cwd.resolve("home");
        Files.createDirectories(project.resolve(RulesLoader.PROJECT_RULES_DIR));
        Files.createDirectories(home.resolve(RulesLoader.GLOBAL_RULES_DIR));
        Files.writeString(project.resolve(RulesLoader.PROJECT_RULES_DIR).resolve("style.md"), "use tabs");
        Files.writeString(project.resolve(RulesLoader.PROJECT_RULES_DIR).resolve("api.md"), "be immutable");
        // index.md orders style.md before api.md.
        Files.writeString(project.resolve(RulesLoader.PROJECT_RULES_DIR).resolve("index.md"),
                "- style.md\n- api.md\n");
        // Use a different home so the project layer is
        // the only one that loads files.
        RulesLoader.load(project, home);
        List<String> paths = RulesLoader.lastLoadedFileNames();
        // The accumulator is filled in document order
        // (index order wins over alphabetical). style.md
        // comes first.
        assertThat(paths).hasSize(2);
        assertThat(paths.get(0)).endsWith("style.md");
        assertThat(paths.get(1)).endsWith("api.md");
        // Paths are absolute (resolved from project).
        assertThat(paths).allMatch(p -> p.startsWith(project.toAbsolutePath().toString()));
    }

    @Test
    void lastLoadedFileNames_omitsDisabledFiles(@TempDir Path cwd) throws IOException {
        Path project = cwd.resolve("project");
        Path home    = cwd.resolve("home");
        Files.createDirectories(project.resolve(RulesLoader.PROJECT_RULES_DIR));
        Files.createDirectories(home.resolve(RulesLoader.GLOBAL_RULES_DIR));
        Files.writeString(project.resolve(RulesLoader.PROJECT_RULES_DIR).resolve("live.md"), "always loaded");
        Files.writeString(project.resolve(RulesLoader.PROJECT_RULES_DIR).resolve("dead.md"),
                "<!-- aethercode: disabled -->\nshould not appear in paths\n");
        RulesLoader.load(project, home);
        List<String> paths = RulesLoader.lastLoadedFileNames();
        // Only the live file is counted. The disabled
        // file was visited but did not contribute to the
        // rendered output.
        assertThat(paths).hasSize(1);
        assertThat(paths.get(0)).endsWith("live.md");
    }

    @Test
    void lastLoadedFileNames_includesBothProjectAndGlobal(@TempDir Path cwd) throws IOException {
        Path project = cwd.resolve("project");
        Path home    = cwd.resolve("home");
        Files.createDirectories(project.resolve(RulesLoader.PROJECT_RULES_DIR));
        Files.createDirectories(home.resolve(RulesLoader.GLOBAL_RULES_DIR));
        Files.writeString(project.resolve(RulesLoader.PROJECT_RULES_DIR).resolve("p.md"), "project rule");
        Files.writeString(home.resolve(RulesLoader.GLOBAL_RULES_DIR).resolve("g.md"), "global rule");
        RulesLoader.load(project, home);
        List<String> paths = RulesLoader.lastLoadedFileNames();
        // The order is project layer first, then global.
        assertThat(paths).hasSize(2);
        assertThat(paths.get(0)).endsWith("p.md");
        assertThat(paths.get(1)).endsWith("g.md");
    }

    @Test
    void lastLoadedFileNames_isClearedBetweenLoads(@TempDir Path cwd) throws IOException {
        Path project = cwd.resolve("project");
        Path home    = cwd.resolve("home");
        Files.createDirectories(project.resolve(RulesLoader.PROJECT_RULES_DIR));
        Files.createDirectories(home.resolve(RulesLoader.GLOBAL_RULES_DIR));
        // First load: 2 files. Second load: 0 files. The
        // second call's result must NOT include the
        // first call's paths.
        Files.writeString(project.resolve(RulesLoader.PROJECT_RULES_DIR).resolve("a.md"), "x");
        Files.writeString(project.resolve(RulesLoader.PROJECT_RULES_DIR).resolve("b.md"), "y");
        RulesLoader.load(project, home);
        assertThat(RulesLoader.lastLoadedFileNames()).hasSize(2);

        // Delete both files before the second load so
        // the load finds nothing.
        Files.delete(project.resolve(RulesLoader.PROJECT_RULES_DIR).resolve("a.md"));
        Files.delete(project.resolve(RulesLoader.PROJECT_RULES_DIR).resolve("b.md"));
        RulesLoader.load(project, home);
        assertThat(RulesLoader.lastLoadedFileNames())
                .as("a follow-up load with no files must clear the accumulator")
                .isEmpty();
    }

    @Test
    void lastLoadedFileNames_isImmutable(@TempDir Path cwd) throws IOException {
        Path project = cwd.resolve("project");
        Path home    = cwd.resolve("home");
        Files.createDirectories(project.resolve(RulesLoader.PROJECT_RULES_DIR));
        Files.createDirectories(home.resolve(RulesLoader.GLOBAL_RULES_DIR));
        Files.writeString(project.resolve(RulesLoader.PROJECT_RULES_DIR).resolve("a.md"), "x");
        RulesLoader.load(project, home);
        List<String> paths = RulesLoader.lastLoadedFileNames();
        // The returned list is an immutable copy —
        // mutating it must throw, not silently corrupt
        // the next load.
        assertThatThrownBy(() -> paths.add("/another/path"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
