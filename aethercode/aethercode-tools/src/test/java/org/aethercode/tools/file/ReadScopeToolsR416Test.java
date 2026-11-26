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

package org.aethercode.tools.file;

import org.aethercode.core.app.AppState;
import org.aethercode.core.scope.ReadScope;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R416: the READ tools enforce the READ SCOPE.
 *
 * <h2>★ WHAT WAS BROKEN ★</h2>
 * <ul>
 *   <li>{@code grep} and {@code glob} had <b>no boundary check at all</b>
 *       — {@code base_dir} could point anywhere on the filesystem. The
 *       model used exactly that to go looking for a build system OUTSIDE
 *       the tree (R415's K8 found the bench harness itself);</li>
 *   <li>all three read tools resolved a relative path against the JVM's
 *       {@code user.dir} — the directory the daemon happened to start in
 *       — so a session pointed at {@code D:/tmp/proj} asking for
 *       {@code src/Foo.java} read {@code <daemon launch dir>/src/Foo.java}.
 *       R415 fixed the write side; the read side kept the defect.</li>
 * </ul>
 *
 * <h2>★ WHY THE SCOPE IS INJECTED HERE ★</h2>
 * <p>This machine sets {@code AETHERCODE_ALLOW_ANY_PATH=1} at the USER
 * level. A tool-level test that let the scope read that variable would
 * measure the ESCAPED behaviour — every "the boundary holds" case would
 * pass while the boundary was doing nothing. So the scope is injected
 * explicitly, with the escape OFF.
 */
class ReadScopeToolsR416Test {

    private static Tool.CallContext ctxIn(Path cwd) {
        return ctxIn(cwd, false);
    }

    private static Tool.CallContext ctxIn(Path cwd, boolean escape) {
        Tool.CallContext ctx = Tool.CallContext.of("s1");
        ctx.setExtra("app_state", new AppState("s1", cwd));
        ctx.setExtra(ReadScope.EXTRAS_KEY, new ReadScope(cwd, escape));
        return ctx;
    }

    private static Map<String, Object> input(String key, String value) {
        Map<String, Object> in = new HashMap<>();
        in.put(key, value);
        return in;
    }

    // ------------------------------------------------------------------
    // the boundary
    // ------------------------------------------------------------------

    @Test
    void fileReadRefusesAPathOutsideTheTree(@TempDir Path root) throws IOException {
        Path outside = root.getParent().resolve("outside-read-" + root.getFileName() + ".txt");
        Files.writeString(outside, "secret\n");
        try {
            Tool.ToolResult r = FileReadTool.call(input("file_path", outside.toString()), ctxIn(root));
            assertThat(r.isError()).isTrue();
            assertThat(r.output().toString()).contains("read refused");
            assertThat(r.output().toString()).contains("outside the working directory");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void grepRefusesABaseDirOutsideTheTree(@TempDir Path root) {
        Path outside = root.getParent().resolve("outside-grep-" + root.getFileName());
        Map<String, Object> in = input("base_dir", outside.toString());
        in.put("pattern", "anything");
        Tool.ToolResult r = GrepTool.call(in, ctxIn(root));
        assertThat(r.isError()).isTrue();
        assertThat(r.output().toString()).contains("read refused");
    }

    @Test
    void globRefusesABaseDirOutsideTheTree(@TempDir Path root) {
        Path outside = root.getParent().resolve("outside-glob-" + root.getFileName());
        Map<String, Object> in = input("base_dir", outside.toString());
        in.put("pattern", "**/*");
        Tool.ToolResult r = GlobTool.call(in, ctxIn(root));
        assertThat(r.isError()).isTrue();
        assertThat(r.output().toString()).contains("read refused");
    }

    // ------------------------------------------------------------------
    // the resolution — a relative path is relative to the SESSION
    // ------------------------------------------------------------------

    @Test
    void aRelativeReadLandsInTheSessionDirectory(@TempDir Path root) throws IOException {
        // ★ THE DEFECT ★ a relative path used to resolve against the
        // JVM's user.dir — the daemon's launch directory — so the read
        // aimed somewhere else entirely (and was then refused as
        // "outside the working directory").
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/AppConfig.java"), "class AppConfig {}\n");

        Tool.ToolResult r = FileReadTool.call(input("file_path", "src/AppConfig.java"), ctxIn(root));
        assertThat(r.isError()).isFalse();
        assertThat(r.output().toString()).contains("class AppConfig");
    }

    @Test
    void grepAndGlobSearchTheSessionDirectory(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/AppConfig.java"), "class AppConfig {}\n");

        Tool.ToolResult g = GrepTool.call(input("pattern", "AppConfig"), ctxIn(root));
        assertThat(g.isError()).isFalse();
        assertThat(g.output().toString()).contains("AppConfig");

        Tool.ToolResult b = GlobTool.call(input("pattern", "**/*.java"), ctxIn(root));
        assertThat(b.isError()).isFalse();
        assertThat(b.output().toString()).contains("AppConfig.java");
    }

    @Test
    void grepAndGlobAcceptABaseDirInsideTheTree(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/AppConfig.java"), "class AppConfig {}\n");

        Tool.ToolResult g = GrepTool.call(input("pattern", "AppConfig"), ctxIn(root));
        assertThat(g.isError()).isFalse();
        assertThat(g.output().toString()).contains("AppConfig");

        Tool.ToolResult b = GlobTool.call(input("pattern", "**/*.java"), ctxIn(root));
        assertThat(b.isError()).isFalse();
        assertThat(b.output().toString()).contains("AppConfig.java");
    }

    // ------------------------------------------------------------------
    // the escape hatch — allowed, but loud
    // ------------------------------------------------------------------

    @Test
    void withTheHatchOpenTheReadHappensAndSaysSo(@TempDir Path root) throws IOException {
        Path outside = root.getParent().resolve("outside-hatch-" + root.getFileName() + ".txt");
        Files.writeString(outside, "visible\n");
        try {
            Tool.ToolResult r = FileReadTool.call(input("file_path", outside.toString()),
                    ctxIn(root, true));
            assertThat(r.isError()).isFalse();
            assertThat(r.output().toString()).contains("visible");
            assertThat(r.output().toString())
                    .as("the user must be able to see that the boundary was off")
                    .contains("read scope is disabled");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    // ------------------------------------------------------------------
    // the scope does not over-reach
    // ------------------------------------------------------------------

    @Test
    void readingAToolOwnedDirectoryIsFine(@TempDir Path root) throws IOException {
        // ★ READS AND WRITES HAVE DIFFERENT RULES ★ the write scope
        // refuses .git/ and target/; reading them is legitimate work
        // ("why did the build fail?" → read the surefire report).
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "[core]\n");
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/report.txt"), "the report\n");

        Tool.ToolResult a = FileReadTool.call(input("file_path", ".git/config"), ctxIn(root));
        assertThat(a.isError()).isFalse();
        assertThat(a.output().toString()).contains("[core]");

        Tool.ToolResult b = FileReadTool.call(input("file_path", "target/report.txt"), ctxIn(root));
        assertThat(b.isError()).isFalse();
        assertThat(b.output().toString()).contains("the report");
    }
}
