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

package org.aethercode.tools.shell;

import org.aethercode.core.app.AppState;
import org.aethercode.core.tool.Tool;
import org.aethercode.core.scope.ShellWriteDetector;
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
 * R415: the bash tool — the last write channel, and its working
 * directory.
 *
 * <h2>★ THE HOLE IN THE SCOPE ★</h2>
 * <p>The command TEXT was checked for {@code cd ..}, but the {@code cwd}
 * PARAMETER was not checked at all. So the one hole was the easiest one
 * to walk through: ask for a command in a directory outside the tree, and
 * every write scope in the product stops applying — because the scope
 * describes where a write may land, and the command was already outside.
 */
class BashToolR415Test {

    @TempDir Path root;

    private Tool.CallContext ctxIn(Path cwd) {
        Tool.CallContext ctx = Tool.CallContext.of("s1");
        ctx.setExtra("app_state", new AppState("s1", cwd));
        ctx.setExtra(WriteScope.EXTRAS_KEY, new WriteScope(cwd, "", false));
        ctx.setExtra(ShellWriteDetector.EXTRAS_KEY, new ShellWriteDetector());
        return ctx;
    }

    private static Map<String, Object> input(String command) {
        Map<String, Object> in = new HashMap<>();
        in.put("command", command);
        return in;
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /** A command that writes one declaration into a file in the cwd. */
    private static String writeCommand(String file) {
        return isWindows()
                ? "echo public static final int MAX_RETRIES = 5;>" + file
                : "printf 'public static final int MAX_RETRIES = 5;\\n' > " + file;
    }

    // ------------------------------------------------------------------
    // the working directory is inside the boundary
    // ------------------------------------------------------------------

    @Test
    void aWorkingDirectoryOutsideTheTreeIsRefused() throws IOException {
        // ★ THE HOLE ★ the parameter was never checked.
        Path outside = root.getParent().resolve("outside-bash-" + root.getFileName());
        Files.createDirectories(outside);
        try {
            Map<String, Object> in = input("echo hi");
            in.put("cwd", outside.toString());
            Tool.ToolResult r = BashTool.call(in, ctxIn(root));
            assertThat(r.isError()).isTrue();
            assertThat(r.output().toString()).contains("outside the session's working directory");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void aRelativeWorkingDirectoryLandsInsideTheSession() throws IOException {
        // ★ THE SAME DEFECT AS THE WRITE TOOLS ★ a relative path used to
        // resolve against the JVM's launch directory.
        Files.createDirectories(root.resolve("src"));
        Map<String, Object> in = input("echo hi");
        in.put("cwd", "src");
        Tool.ToolResult r = BashTool.call(in, ctxIn(root));
        assertThat(r.isError()).isFalse();
    }

    // ------------------------------------------------------------------
    // the shell channel is observed
    // ------------------------------------------------------------------

    @Test
    void aCommandThatChangesAConstantIsReported() throws IOException {
        // ★ THE CHANNEL THAT USED TO BE INVISIBLE ★ the command rewrote
        // a file behind the engine's back; the tool result now says so.
        Path file = root.resolve("AppConfig.java");
        Files.writeString(file, "public class C {\n"
                + "    public static final int MAX_RETRIES = 3;\n"
                + "}\n");

        Tool.ToolResult r = BashTool.call(input(writeCommand("AppConfig.java")), ctxIn(root));

        assertThat(r.isError()).isFalse();
        String out = r.output().toString();
        assertThat(out).contains("SHELL WRITE DETECTED");
        assertThat(out).contains("AppConfig.java");
        assertThat(out).contains("MAX_RETRIES");
    }

    @Test
    void aCommandThatChangesNothingIsNotReported() throws IOException {
        Tool.ToolResult r = BashTool.call(input("echo hello"), ctxIn(root));
        assertThat(r.isError()).isFalse();
        assertThat(r.output().toString()).doesNotContain("SHELL WRITE DETECTED");
    }

    // ------------------------------------------------------------------
    // the command text is a path too
    // ------------------------------------------------------------------

    @Test
    void aParentDirectoryPathIsRefused() throws IOException {
        // ★ THE LAST HOLE IN THE SHELL ★ the command names no absolute
        // path and runs no `cd` — so it sailed past every check — and
        // yet it writes one directory up, outside the boundary.
        Tool.ToolResult r = BashTool.call(input("echo hello > ../escape.txt"), ctxIn(root));
        assertThat(r.isError()).isTrue();
        assertThat(r.output().toString()).contains("parent-directory path");
    }

    @Test
    void aRedirectOutsideTheTreeIsRefused() throws IOException {
        // ★ A REDIRECT IS A WRITE ★ and it names its target right there.
        // ★ THE TARGET MUST BE UNIQUE ★ a fixed name in the shared temp
        // root is inherited by the NEXT run: a mutation that disabled
        // this check once wrote the file, and every later run then found
        // it already there — a test that measures the leftovers of an
        // earlier run is measuring the wrong thing.
        Path outside = root.resolveSibling("r415-redirect-" + root.getFileName() + ".txt");
        try {
            Tool.ToolResult r = BashTool.call(input("echo hello > " + outside.toString()), ctxIn(root));
            assertThat(r.isError()).isTrue();
            assertThat(r.output().toString()).contains("redirects output");
            assertThat(Files.exists(outside)).isFalse();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void aRedirectInsideTheTreeIsFine() throws IOException {
        Tool.ToolResult r = BashTool.call(input("echo hello > notes.txt"), ctxIn(root));
        assertThat(r.isError()).isFalse();
        assertThat(root.resolve("notes.txt")).exists();
    }

    @Test
    void aQuotedGreaterThanIsNotARedirect() throws IOException {
        // ★ NO FALSE POSITIVES ★ a quote is how a shell says "this is
        // TEXT"; a boundary that refuses `grep "a > b"` gets switched
        // off.
        Tool.ToolResult r = BashTool.call(input("echo \"a > b\""), ctxIn(root));
        assertThat(r.isError()).isFalse();
    }

    // ------------------------------------------------------------------
    // ★ R415b: WHAT THE LIVE MATRIX FOUND IN THIS ROUND'S OWN RULE ★
    // ------------------------------------------------------------------

    @Test
    void aChainedParentDirectoryClimbIsRefused() throws IOException {
        // ★ THE HOLE ★ the rule was `command.startsWith("cd ..")`, so a
        // CHAINED climb walked straight past it. A `cd` changes the
        // working directory of the WHOLE invocation, so the rest of the
        // chain writes one level above the tree — the exact escape the
        // scope claims to close.
        //
        // ★ THE FILE NAME MUST BE UNIQUE ★ a fixed name in the shared
        // temp root is inherited by the NEXT run (see the redirect test
        // above for the same reason).
        String name = "r415-climb-" + root.getFileName() + ".txt";
        Path escaped = root.getParent().resolve(name);
        try {
            Tool.ToolResult r = BashTool.call(
                    input("echo start && cd .. && echo escaped > " + name), ctxIn(root));
            assertThat(r.isError()).isTrue();
            assertThat(r.output().toString()).contains("climbs above the engine cwd");
            // ★ AND THE CONSEQUENCE ★ the hole's damage was a file one
            // level up. The rule is not only about the message.
            assertThat(Files.exists(escaped)).isFalse();
        } finally {
            Files.deleteIfExists(escaped);
        }
    }

    @Test
    void aRedirectToTheNullDeviceIsAllowed() throws IOException {
        // ★ THE NULL DEVICE IS NOT A WRITE OUTSIDE THE TREE ★
        // `cmd > /dev/null` is the unix way of saying "discard the
        // output"; it writes nowhere. (MEASURED on Windows: cmd.exe
        // refuses the path itself — "The system cannot find the path
        // specified." — and creates no D:\dev\null.)
        //
        // ★ THE COST OF GETTING IT WRONG ★ the live matrix measured a
        // model being refused for an ordinary `... 2>/dev/null`, and a
        // boundary that refuses the ordinary idiom of silencing a
        // command gets switched off.
        Tool.ToolResult r = BashTool.call(input("echo hello > /dev/null"), ctxIn(root));
        assertThat(r.output().toString()).doesNotContain("refused by sandbox");
    }

    // ------------------------------------------------------------------
    // ★ R415c: THE HOLE THE LIVE MATRIX FOUND ★
    // ------------------------------------------------------------------

    @Test
    void aCopyToAnAbsolutePathOutsideTheTreeIsRefused() throws IOException {
        // ★ THE ESCAPE, REPRODUCED ★ the L1 scenario measured it: the
        // model was refused by file_write, staged the file INSIDE the
        // tree, and copied it out — `cp .stage "C:\…\escape.txt"` — an
        // absolute path outside the tree, written to, and the boundary
        // did not notice. The rules above covered a REDIRECT and a ".."
        // segment; a COPY is a write too.
        Path staged = root.resolve(".stage.txt");
        Files.writeString(staged, "payload");
        Path outside = root.resolveSibling("r415-copy-" + root.getFileName() + ".txt");
        try {
            Tool.ToolResult r = BashTool.call(
                    input("cp .stage.txt \"" + outside.toString() + "\""), ctxIn(root));
            assertThat(r.isError()).isTrue();
            assertThat(r.output().toString()).contains("outside the working directory");
            // ★ AND THE CONSEQUENCE ★ the hole's damage was a file one
            // level up. The rule is not only about the message.
            assertThat(Files.exists(outside)).isFalse();
        } finally {
            Files.deleteIfExists(outside);
            Files.deleteIfExists(staged);
        }
    }

    @Test
    void aMutatingCommandMayNameAnAbsolutePathInsideTheTree() throws IOException {
        // ★ NO FALSE POSITIVES ★ the tree is the boundary, not the
        // drive: a build inside the tree names absolute paths all the
        // time (the compiler, the classpath, the source file) and must
        // keep working.
        Path src = root.resolve("inside-src.txt");
        Files.writeString(src, "payload");
        Path dst = root.resolve("inside-dst.txt");
        Tool.ToolResult r = BashTool.call(
                input("cp \"" + src.toString() + "\" \"" + dst.toString() + "\""), ctxIn(root));
        assertThat(r.output().toString()).doesNotContain("refused by sandbox");
        assertThat(dst).exists();
    }

    @Test
    void aSiblingWithTheSamePrefixIsNotInside() throws IOException {
        // ★ A BOUNDARY THAT IS A STRING COMPARISON IS NOT A BOUNDARY ★
        // "…\sb-L1X" starts with "…\sb-L1" as a STRING and is not inside
        // it as a DIRECTORY. The same one-letter bug the write scope's
        // own path helper had.
        Path sibling = root.resolveSibling(root.getFileName() + "X");
        Files.createDirectories(sibling);
        try {
            Path staged = root.resolve(".stage2.txt");
            Files.writeString(staged, "payload");
            Tool.ToolResult r = BashTool.call(
                    input("cp .stage2.txt \"" + sibling.resolve("out.txt").toString() + "\""),
                    ctxIn(root));
            assertThat(r.isError()).isTrue();
            assertThat(sibling.resolve("out.txt")).doesNotExist();
            Files.deleteIfExists(staged);
        } finally {
            try (java.util.stream.Stream<Path> s = Files.walk(sibling)) {
                s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                });
            }
        }
    }

    @Test
    void aReadOnlyCommandMayLookOutsideTheTree() throws IOException {
        // ★ THE QUALIFIER IS THE POINT ★ a command that cannot write may
        // look wherever it likes; only a command that CAN write is held
        // to the tree.
        Tool.ToolResult r = BashTool.call(
                input("ls \"" + root.resolveSibling("elsewhere-" + root.getFileName()).toString() + "\""),
                ctxIn(root));
        assertThat(r.output().toString()).doesNotContain("refused by sandbox");
    }
}
