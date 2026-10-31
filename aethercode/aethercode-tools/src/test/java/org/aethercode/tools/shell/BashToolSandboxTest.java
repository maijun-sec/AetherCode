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

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tests for the BashTool scope-escape sandbox. The
 * sandbox is a coarse substring heuristic — it refuses
 * {@code cd ..} / {@code pushd ..} and any {@code cd} to
 * an absolute path outside the engine's cwd. The
 * {@code AETHERCODE_BASH_ALLOW_SCOPE_ESCAPE=1} env var
 * disables it (test + opt-out case).
 */
class BashToolSandboxTest {

    @Test
    void cdDotDotBlocked(@TempDir Path tmp) {
        Tool.ToolResult r = BashTool.call(
                Map.of("command", "cd ..", "cwd", tmp.toString()),
                Tool.CallContext.of("s"));
        assertThat(r.isError()).isTrue();
        assertThat(r.output().toString()).contains("sandbox");
    }

    @Test
    void pushdDotDotBlocked(@TempDir Path tmp) {
        Tool.ToolResult r = BashTool.call(
                Map.of("command", "pushd ..", "cwd", tmp.toString()),
                Tool.CallContext.of("s"));
        assertThat(r.isError()).isTrue();
    }

    @Test
    void cdAbsoluteInsideCwdAllowed(@TempDir Path tmp) throws Exception {
        File inside = tmp.resolve("subdir").toFile();
        // The cd may fail (subdir doesn't exist), but the
        // sandbox should NOT refuse it.
        Tool.ToolResult r = BashTool.call(
                Map.of("command",
                        isWindows() ? "cd " + inside.getAbsolutePath() : "cd " + inside.getAbsolutePath(),
                        "cwd", tmp.toString()),
                Tool.CallContext.of("s"));
        // Either the cd succeeded (and a follow-up command
        // is needed) or it failed because the dir doesn't
        // exist — but the failure should be from the OS, not
        // the sandbox.
        assertThat(r.output().toString()).doesNotContain("refusing");
    }

    @Test
    void cdAbsoluteOutsideCwdBlocked(@TempDir Path tmp) {
        // The temp parent (e.g. C:\Users\xxx\AppData\Local\Temp\)
        // is outside the test's temp dir, so cd .. goes there.
        // Use a fixed absolute path that we're sure is outside.
        String outside = isWindows()
                ? tmp.getParent().getParent().toString()
                : "/etc";
        Tool.ToolResult r = BashTool.call(
                Map.of("command", "cd " + outside, "cwd", tmp.toString()),
                Tool.CallContext.of("s"));
        assertThat(r.isError()).isTrue();
        assertThat(r.output().toString()).contains("sandbox");
    }

    @Test
    void unrelatedCommandsNotBlocked(@TempDir Path tmp) {
        // Plain commands (no cd) are unaffected.
        Tool.ToolResult r = BashTool.call(
                Map.of("command", isWindows() ? "echo hi" : "echo hi",
                        "cwd", tmp.toString()),
                Tool.CallContext.of("s"));
        assertThat(r.isError()).isFalse();
        assertThat(r.output().toString()).contains("hi");
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name");
        return os != null && os.toLowerCase().contains("win");
    }
}
