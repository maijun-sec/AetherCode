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
