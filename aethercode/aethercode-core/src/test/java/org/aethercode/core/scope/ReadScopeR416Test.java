/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.core.scope;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R416 — the READ SCOPE's three rules.
 *
 * <h2>★ WHY THIS SCOPE EXISTS ★</h2>
 * <p>R415 drew a hard boundary around WRITES and left reads alone. The
 * survey behind that round found what "left alone" means in practice:
 * {@code grep} and {@code glob} had <b>no boundary check at all</b>, and
 * all three read tools resolved a relative path against the JVM's
 * {@code user.dir} instead of the session's working directory.
 *
 * <h2>★ THE THREE RULES ★</h2>
 * <ul>
 *   <li><b>R1</b> — the tree is the boundary;</li>
 *   <li><b>R2</b> — the engine's own offload root stays readable (the
 *       engine tells the model to {@code file_read} that path — a scope
 *       that refused it would break the offload feature);</li>
 *   <li><b>R3</b> — a symlink may not lead out of the tree.</li>
 * </ul>
 */
class ReadScopeR416Test {

    // ------------------------------------------------------------------
    // R1 — the tree is the boundary
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R416: a path inside the tree is allowed")
    void aPathInsideTheTreeIsAllowed(@TempDir Path root) {
        ScopeDecision d = new ReadScope(root, false).check(root.resolve("src/AppConfig.java"));
        assertThat(d.allowed()).isTrue();
        assertThat(d.reason()).isNull();
    }

    @Test
    @DisplayName("R416: a path outside the tree is refused")
    void aPathOutsideTheTreeIsRefused(@TempDir Path root) {
        Path outside = root.getParent().resolve("elsewhere-" + root.getFileName() + ".java");
        ScopeDecision d = new ReadScope(root, false).check(outside);
        assertThat(d.allowed()).isFalse();
        assertThat(d.message()).contains("outside the working directory");
        assertThat(d.message()).contains("read refused");
    }

    @Test
    @DisplayName("R416: a sibling with the same prefix is outside the tree")
    void aSiblingWithTheSamePrefixIsOutside(@TempDir Path root) {
        // ★ THE ONE-LETTER ESCAPE ★ "…/abc" is not the parent of "…/abcde".
        Path sibling = root.resolveSibling(root.getFileName() + "de");
        ScopeDecision d = new ReadScope(root, false).check(sibling.resolve("File.java"));
        assertThat(d.allowed()).isFalse();
    }

    @Test
    @DisplayName("R416: a plain out-of-tree path is refused AS out-of-tree, not as a link")
    void aPlainOutOfTreePathIsNotCalledALink(@TempDir Path root) throws IOException {
        // ★ THE ORDER IS THE DIAGNOSIS ★ R416's live matrix produced the
        // wrong message: the model globbed ONE DIRECTORY above the
        // sandbox and was told "points at …, which is outside the
        // working directory (…). A link may not lead out of the tree."
        // There was no link — the path simply was outside. A message
        // that names the wrong mechanism sends the model looking for the
        // wrong thing (R383's lesson: the diagnosis decides what the
        // model does next).
        //
        // ⇒ R1 (the literal path) must be checked BEFORE R3 (the real
        // path), and this case pins that order.
        Path outside = Files.createTempDirectory(root.getParent(), "r416-plain-");
        try {
            ScopeDecision d = new ReadScope(root, false).check(outside.resolve("data.txt"));
            assertThat(d.allowed()).isFalse();
            assertThat(d.message())
                    .as("the refusal must name the tree boundary")
                    .contains("outside the working directory");
            assertThat(d.message())
                    .as("a path that is simply outside is NOT a link — saying so sends the model hunting for a link that does not exist")
                    .doesNotContain("link");
        } finally {
            Files.deleteIfExists(outside.resolve("data.txt"));
            Files.deleteIfExists(outside);
        }
    }

    // ------------------------------------------------------------------
    // the escape hatch — the third state
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R416: with the hatch open the read happens AND says so")
    void theEscapeHatchIsLoud(@TempDir Path root) {
        Path outside = root.getParent().resolve("elsewhere-" + root.getFileName() + ".java");
        ScopeDecision d = new ReadScope(root, true).check(outside);
        assertThat(d.allowed())
                .as("the user switched the boundary off — the read happens").isTrue();
        assertThat(d.reason())
                .as("but the rule that was broken is still known").isNotNull();
        assertThat(d.escapeNotice())
                .as("a boundary that silently stops being a boundary is worse than no boundary")
                .contains(WriteScope.ALLOW_ANY_PATH_ENV)
                .contains("read scope is disabled");
        assertThat(d.applyNotice("body")).contains("body").contains("read scope is disabled");
    }

    @Test
    @DisplayName("R416: an allowed read carries no escape notice")
    void anAllowedReadCarriesNoNotice(@TempDir Path root) {
        ScopeDecision d = new ReadScope(root, false).check(root.resolve("a.txt"));
        assertThat(d.escapeNotice()).isNull();
        assertThat(d.applyNotice("body")).isEqualTo("body");
    }

    // ------------------------------------------------------------------
    // R2 — the engine's own offload root stays readable
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R416: the offload root is readable — the engine told the model to read it")
    void theOffloadRootStaysReadable(@TempDir Path root) {
        Path offload = SessionPaths.offloadRoot();
        assertThat(offload)
                .as("the offload root must resolve — the executor writes there and the model reads back")
                .isNotNull();
        ScopeDecision d = new ReadScope(root, false).check(offload.resolve("s1/large_tool_results/call_1"));
        assertThat(d.allowed())
                .as("the offload root is outside the tree, but the engine's own stub points there")
                .isTrue();
    }

    @Test
    @DisplayName("R416: the offload root is ONE implementation")
    void theOffloadRootIsOneImplementation() {
        // the executor and the read scope must agree on the directory;
        // two answers to one question drift.
        Path offload = SessionPaths.offloadRoot();
        assertThat(offload).isNotNull();
        assertThat(offload.getFileName().toString()).isEqualTo("sessions");
        assertThat(offload.getParent().getFileName().toString()).isEqualTo(".aethercode");
    }

    // ------------------------------------------------------------------
    // R3 — a link may not lead out of the tree
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R416: a link inside the tree that points outside is refused")
    void aLinkOutOfTheTreeIsRefused(@TempDir Path root) throws IOException {
        // ★ THE LINK MUST POINT AT A DIRECTORY ★ a directory link is
        // what the operating system offers without special privileges,
        // and the rule is the same for both kinds: the path says
        // "inside"; the file it points at does not.
        Path outside = Files.createTempDirectory(root.getParent(), "r416-outside-");
        Path link = root.resolve("innocent");
        try {
            Files.writeString(outside.resolve("secret.txt"), "the secret\n");
            if (!createLink(link, outside)) {
                // Neither mechanism is available — say so rather than
                // passing vacuously. (R416's mutation bench measured
                // what a vacuous pass costs: the mutation that turns
                // this rule off SURVIVED, because the test was skipped
                // and a skipped test reports success.)
                org.junit.jupiter.api.Assumptions.assumeTrue(false,
                        "this filesystem supports neither symbolic links nor directory junctions");
                return;
            }
            Path through = link.resolve("secret.txt");
            assertThat(Files.exists(through))
                    .as("the link must really lead to the file — otherwise the rule is not exercised")
                    .isTrue();
            ScopeDecision d = new ReadScope(root, false).check(through);
            assertThat(d.allowed())
                    .as("the path says 'inside'; the file it points at does not")
                    .isFalse();
            assertThat(d.message()).contains("points at");
        } finally {
            deleteLink(link);
            deleteTree(outside);
        }
    }

    /**
     * R416: create a link that points at {@code target}.
     *
     * <p>★ TWO MECHANISMS, ONE RULE ★ a symbolic link is the obvious
     * one, but creating one needs a privilege a normal user does not
     * have on Windows ("此操作需要管理员权限"), so the rule would be
     * untested exactly where it is most likely to be needed. A
     * **directory junction** needs none — and it is the same shape: the
     * path says "inside", the file it points at does not.
     *
     * <p>★ AND THE JVM DOES RESOLVE IT ★ measured, not assumed:
     * {@code Path.toRealPath()} on a Windows junction returns the
     * TARGET's real path, which is what the scope compares.
     *
     * @return true when a link was created
     */
    private static boolean createLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (UnsupportedOperationException | IOException e) {
            // no privilege for a symbolic link — fall through to the junction
        }
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            return false;
        }
        try {
            Process p = new ProcessBuilder(
                    "powershell", "-NoProfile", "-Command",
                    "New-Item -ItemType Junction -Path '" + link + "' -Target '" + target
                            + "' -ErrorAction Stop | Out-Null")
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor() == 0 && Files.exists(link);
        } catch (Exception e) {
            return false;
        }
    }

    /** Remove the link itself (never its target). */
    private static void deleteLink(Path link) {
        try {
            Files.deleteIfExists(link);
        } catch (IOException ignored) {
            // the temp directory is removed by the OS anyway
        }
    }

    /** Remove a directory tree, deepest first. */
    private static void deleteTree(Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
