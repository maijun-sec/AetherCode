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
import org.aethercode.core.tool.Tool;
import org.aethercode.core.scope.WriteScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R415: the write tools enforce the SCOPE.
 *
 * <h2>★ WHY THE SCOPE IS INJECTED HERE ★</h2>
 * <p>This machine sets {@code AETHERCODE_ALLOW_ANY_PATH=1} at the USER
 * level. A tool-level test that let the scope read that variable would
 * measure the ESCAPED behaviour — every "the boundary holds" case would
 * pass while the boundary was doing nothing. So the scope is injected
 * explicitly, with the escape OFF.
 */
class WriteScopeToolsR415Test {

    private static Tool.CallContext ctxIn(Path cwd) {
        Tool.CallContext ctx = Tool.CallContext.of("s1");
        ctx.setExtra("app_state", new AppState("s1", cwd));
        ctx.setExtra(WriteScope.EXTRAS_KEY, new WriteScope(cwd, "", false));
        return ctx;
    }

    private static Map<String, Object> input(String path, String content) {
        Map<String, Object> in = new HashMap<>();
        in.put("file_path", path);
        if (content != null) in.put("content", content);
        return in;
    }

    // ------------------------------------------------------------------
    // W1 — the tree is the boundary
    // ------------------------------------------------------------------

    @Test
    void fileWriteRefusesAPathOutsideTheTree(@TempDir Path root) throws IOException {
        Path outside = root.getParent().resolve("outside-" + root.getFileName() + ".java");
        Tool.ToolResult r = FileWriteTool.call(input(outside.toString(), "x"), ctxIn(root));
        assertThat(r.isError()).isTrue();
        assertThat(r.output().toString()).contains("outside the working directory");
    }

    @Test
    void fileEditRefusesAPathOutsideTheTree(@TempDir Path root) throws IOException {
        // ★ THE DEFECT THIS ROUND FOUND ★ file_edit resolved the path and
        // wrote it, with NO scope check at all: it could edit any file on
        // the whole filesystem, while ToolSafeList's comment claimed it
        // "refuses paths outside the configured cwd".
        Path outside = root.getParent().resolve("outside-edit-" + root.getFileName() + ".java");
        Files.writeString(outside, "old\n");
        try {
            Tool.ToolResult r = FileEditTool.call(editInput(outside.toString(), "old", "new"), ctxIn(root));
            assertThat(r.isError()).isTrue();
            assertThat(r.output().toString()).contains("outside the working directory");
            assertThat(Files.readString(outside)).isEqualTo("old\n");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void notebookEditRefusesAPathOutsideTheTree(@TempDir Path root) throws IOException {
        Path outside = root.getParent().resolve("outside-nb-" + root.getFileName() + ".ipynb");
        Files.writeString(outside, "{\"cells\":[]}");
        try {
            Map<String, Object> in = new HashMap<>();
            in.put("notebook_path", outside.toString());
            in.put("operation", "insert");
            in.put("cell_index", 0);
            Tool.ToolResult r =
                    org.aethercode.tools.edit.NotebookEditTool.call(in, ctxIn(root));
            assertThat(r.isError()).isTrue();
            assertThat(r.output().toString()).contains("outside the working directory");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void aRelativePathLandsInTheSessionDirectory(@TempDir Path root) throws IOException {
        // ★ THE FIX ★ a relative path used to resolve against the JVM's
        // user.dir — the daemon's launch directory — so the write aimed
        // somewhere else entirely and was then refused as "outside the
        // working directory".
        Tool.ToolResult r = FileWriteTool.call(input("src/AppConfig.java", "body\n"), ctxIn(root));
        assertThat(r.isError()).isFalse();
        assertThat(root.resolve("src/AppConfig.java")).exists();
        assertThat(Files.readString(root.resolve("src/AppConfig.java"))).isEqualTo("body\n");
    }

    // ------------------------------------------------------------------
    // W2 — tool-owned directories
    // ------------------------------------------------------------------

    @Test
    void fileWriteRefusesTheGitDirectory(@TempDir Path root) {
        Tool.ToolResult r = FileWriteTool.call(input(root.resolve(".git/hooks/pre-commit").toString(), "x"),
                ctxIn(root));
        assertThat(r.isError()).isTrue();
        assertThat(r.output().toString()).contains(".git");
    }

    // ------------------------------------------------------------------
    // W3 — do not invent a build system
    // ------------------------------------------------------------------

    @Test
    void fileWriteRefusesToInventABuildSystem(@TempDir Path root) {
        Tool.ToolResult r = FileWriteTool.call(input(root.resolve("pom.xml").toString(), "<project/>"),
                ctxIn(root));
        assertThat(r.isError()).isTrue();
        assertThat(r.output().toString()).contains("no build system");
        assertThat(root.resolve("pom.xml")).doesNotExist();
    }

    @Test
    void anOrdinaryFileInAProjectWithNoBuildIsFine(@TempDir Path root) {
        // ★ THE SCOPE DOES NOT OVER-REACH ★ asked for one test, the
        // deliverable is one test.
        Tool.ToolResult r = FileWriteTool.call(
                input(root.resolve("src/test/java/demo/LruCacheTest.java").toString(), "class T {}"),
                ctxIn(root));
        assertThat(r.isError()).isFalse();
        assertThat(root.resolve("src/test/java/demo/LruCacheTest.java")).exists();
    }

    // ------------------------------------------------------------------
    // the advisory — the consistency check the model sees
    // ------------------------------------------------------------------

    @Test
    void aWriteThatMissesACopySaysSoInTheResult(@TempDir Path root) throws IOException {
        // ★ THE ADVISORY ★ the engine found a copy that still carries the
        // old value, and the tool result — the model's feedback channel —
        // says so.
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/AppConfig.java"),
                "public class AppConfig {\n"
                        + "    public static final int MAX_RETRIES = 3;\n"
                        + "}\n");
        Files.writeString(root.resolve("src/LegacyConfig.java"),
                "public class LegacyConfig {\n"
                        + "    public static final int MAX_RETRIES = 3;\n"
                        + "}\n");

        Tool.ToolResult r = FileWriteTool.call(input(root.resolve("src/AppConfig.java").toString(),
                "public class AppConfig {\n"
                        + "    public static final int MAX_RETRIES = 5;\n"
                        + "}\n"), ctxIn(root));

        assertThat(r.isError()).isFalse();
        String out = r.output().toString();
        assertThat(out).contains("CONSISTENCY CHECK FAILED");
        assertThat(out).contains("LegacyConfig.java");
    }

    @Test
    void aWriteThatMissesNothingSaysNothingExtra(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/AppConfig.java"),
                "public class AppConfig {\n"
                        + "    public static final int MAX_RETRIES = 3;\n"
                        + "}\n");

        Tool.ToolResult r = FileWriteTool.call(input(root.resolve("src/AppConfig.java").toString(),
                "public class AppConfig {\n"
                        + "    public static final int MAX_RETRIES = 5;\n"
                        + "}\n"), ctxIn(root));

        assertThat(r.isError()).isFalse();
        assertThat(r.output().toString()).doesNotContain("CONSISTENCY CHECK FAILED");
    }

    // ------------------------------------------------------------------
    // helper
    // ------------------------------------------------------------------

    private static Map<String, Object> editInput(String path, String oldStr, String newStr) {
        Map<String, Object> in = new HashMap<>();
        in.put("file_path", path);
        in.put("old_string", oldStr);
        in.put("new_string", newStr);
        return in;
    }
}
