/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.core.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R726 tests for the on-disk context-file hint injected by
 * {@link QueryEngine#scanCwdContextDocs}.
 *
 * <p>The pre-R726 anchor said "if you cannot see [the original
 * prompt], read on-disk plan/progress docs" without telling the
 * model WHICH files actually exist. R726 fixes this by scanning
 * cwd + docs/ + .aethercode/* for known long-task doc filenames
 * (plan.md, progress.md, design.md, R###-*.md, q##-*.json, etc.)
 * and listing the discovered paths in the anchor body.
 *
 * <p>These tests directly exercise the package-private helper
 * {@code QueryEngine.scanCwdContextDocs(Path, int)} so the cases
 * are deterministic and don't depend on a full engine setup.
 */
class QueryEngineAnchorR726Test {

    @TempDir
    Path tmp;

    @BeforeEach
    void cleanEnv() {
        // ensure no env-var override leaks in
    }

    @AfterEach
    void resetEnv() {}

    /** Helper: file separator ('/' on POSIX, '\' on Windows).
     *  Tests use this so assertions work on both platforms. */
    private static final String SEP = java.io.File.separator;
    /** Helper: read as POSIX-slash string regardless of host. */
    private static String posix(String path) {
        return path.replace('\\', '/');
    }

    /** Helper: write a file with content under cwd. */
    private void touch(String rel) throws Exception {
        Path p = tmp.resolve(rel);
        Path parent = p.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Files.writeString(p, "stub");
    }

    @Test
    void scan_returnsEmpty_when_cwdIsNull() {
        List<String> hits = QueryEngine.scanCwdContextDocs(null, 10);
        assertNotNull(hits);
        assertTrue(hits.isEmpty(), "null cwd must produce empty list");
    }

    @Test
    void scan_returnsEmpty_when_cwdDoesNotExist() {
        List<String> hits = QueryEngine.scanCwdContextDocs(tmp.resolve("does-not-exist"), 10);
        assertTrue(hits.isEmpty(), "non-existent cwd must produce empty list");
    }

    @Test
    void scan_finds_knownTopLevelDoc() throws Exception {
        touch("plan.md");
        touch("progress.md");
        touch("design.md");
        touch("unrelated.txt");  // should NOT match

        List<String> hits = QueryEngine.scanCwdContextDocs(tmp, 10);
        assertTrue(hits.size() >= 3, "all three known top-level docs must be discovered: " + hits);

        String all = String.join("\n", hits);
        assertTrue(all.contains("plan.md"), "plan.md must appear in hint");
        assertTrue(all.contains("progress.md"), "progress.md must appear in hint");
        assertTrue(all.contains("design.md"), "design.md must appear in hint");
        assertFalse(all.contains("unrelated.txt"), "unrelated .txt must NOT be listed");
    }

    @Test
    void scan_finds_agentsMd_and_tasks() throws Exception {
        touch("AGENTS.md");
        touch("tasks.md");
        touch("README.md");

        List<String> hits = QueryEngine.scanCwdContextDocs(tmp, 10);
        String all = String.join("\n", hits);
        // case-insensitive — the helper lowercases filenames before matching
        assertTrue(all.toLowerCase().contains("agents.md"),
                "AGENTS.md must be discovered (case-insensitive): " + all);
        assertTrue(all.toLowerCase().contains("tasks.md"),
                "tasks.md must be discovered: " + all);
        assertTrue(all.toLowerCase().contains("readme.md"),
                "README.md must be discovered: " + all);
    }

    @Test
    void scan_finds_roundNumbered_designDocs() throws Exception {
        touch("R720-systematic-repair.md");
        touch("R725-progress.md");
        touch("R719-session-anchor.md");

        List<String> hits = QueryEngine.scanCwdContextDocs(tmp, 10);
        String all = String.join("\n", hits);
        assertTrue(all.contains("R720-systematic-repair.md"),
                "R###-*.md must match: " + all);
        assertTrue(all.contains("R725-progress.md"),
                "R###-*.md must match: " + all);
        assertTrue(all.contains("R719-session-anchor.md"),
                "R###-*.md must match: " + all);
    }

    @Test
    void scan_finds_qNumbered_prompts() throws Exception {
        touch("q14-prompt.json");
        touch("q21-notes.md");
        touch("q24b-quiet-anchor.md");

        List<String> hits = QueryEngine.scanCwdContextDocs(tmp, 10);
        String all = String.join("\n", hits);
        assertTrue(all.contains("q14-prompt.json"),
                "q##-*.json must match: " + all);
        assertTrue(all.contains("q21-notes.md"),
                "q##-*.md must match: " + all);
        assertTrue(all.contains("q24b-quiet-anchor.md"),
                "q##b-*.md must match (multi-suffix allowed): " + all);
    }

    @Test
    void scan_recurses_into_docs_subdir() throws Exception {
        touch("docs/R720-systematic-repair.md");
        touch("docs/R725-progress.md");
        touch("docs/intro.txt");

        List<String> hits = QueryEngine.scanCwdContextDocs(tmp, 10);
        String all = posix(String.join("\n", hits));
        assertTrue(all.contains("docs/R720-systematic-repair.md"),
                "docs/R###-*.md must be discovered: " + all);
        assertTrue(all.contains("docs/intro.txt"),
                "docs/*.txt must be discovered: " + all);
    }

    @Test
    void scan_recurses_into_aethercode_subdirs() throws Exception {
        touch(".aethercode/memory/project.md");
        touch(".aethercode/sessions/session-1.md");
        touch(".aethercode/sdd/spec.md");

        List<String> hits = QueryEngine.scanCwdContextDocs(tmp, 10);
        String all = posix(String.join("\n", hits));
        assertTrue(all.contains(".aethercode/memory/project.md"),
                ".aethercode/memory/*.md must be discovered: " + all);
        assertTrue(all.contains(".aethercode/sessions/session-1.md"),
                ".aethercode/sessions/*.md must be discovered: " + all);
        assertTrue(all.contains(".aethercode/sdd/spec.md"),
                ".aethercode/sdd/*.md must be discovered: " + all);
    }

    @Test
    void scan_respects_cap() throws Exception {
        // create 14 candidate files
        for (int i = 100; i < 114; i++) {
            touch(String.format("R%d-docs.md", i));
        }
        List<String> hits = QueryEngine.scanCwdContextDocs(tmp, 5);
        assertEquals(5, hits.size(),
                "cap=5 must limit hits to 5 even when 14 candidates exist");
    }

    @Test
    void scan_sorts_filenames_descending() throws Exception {
        touch("R100-old.md");
        touch("R900-newer.md");
        touch("R750-middle.md");

        List<String> hits = QueryEngine.scanCwdContextDocs(tmp, 10);
        assertEquals(3, hits.size());

        // base filenames (last segment after '/' or '\')
        String first  = baseName(hits.get(0));
        String second = baseName(hits.get(1));
        String third  = baseName(hits.get(2));
        // Lex DESC means "R900" (9 > 7 > 1) comes first
        assertEquals("R900-newer.md", first,
                "newest R-numbered doc must sort first: " + hits);
        assertEquals("R750-middle.md", second,
                "middle R-numbered doc must sort second: " + hits);
        assertEquals("R100-old.md", third,
                "oldest R-numbered doc must sort last: " + hits);
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    @Test
    void scan_silentlySwallows_ioExceptions() {
        // a path that resolves but doesn't have permission — on Windows this
        // is hard to construct, so we just confirm the API never throws for
        // bizarre inputs.
        List<String> hits = QueryEngine.scanCwdContextDocs(
                tmp.resolve("../") /* parent dir */, 10);
        // may or may not return hits — must not throw
        assertNotNull(hits);
    }

    @Test
    void scan_mixed_realtree_returnsAllSources() throws Exception {
        // Mimic a real AetherCode repo cwd layout.
        touch("plan.md");
        touch("R726-anchor-hint.md");
        touch("R725-progress.md");
        touch("docs/R720-systematic-repair.md");
        touch("docs/R721-R725-systematic-completion.md");
        touch(".aethercode/AGENTS.md");

        List<String> hits = QueryEngine.scanCwdContextDocs(tmp, 20);
        assertTrue(hits.size() >= 6, "must surface every source: " + hits);
        String all = posix(String.join("\n", hits));
        assertTrue(all.contains("plan.md"));
        assertTrue(all.contains("R726-anchor-hint.md"));
        assertTrue(all.contains("R725-progress.md"));
        assertTrue(all.contains("docs/"));
        assertTrue(all.contains(".aethercode/"));
    }
}
