/*
 * R326 — `mkdir -p` on Windows cmd.exe creates a literal
 * "-p" folder instead of recursive mkdir. The bash tool now
 * detects `mkdir -p` on Windows and rewrites to PowerShell's
 * `New-Item -ItemType Directory -Force`. This test pins the
 * detection + rewrite logic via the public surface.
 */
package org.aethercode.tools.shell;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.*;

class BashToolR326MkdirPTest {

    /** The detection helper is package-private — exercise it
     *  directly so we cover the rewrite table without
     *  depending on OS. */
    @Test
    void detectsMkdirDashP() {
        assertTrue(BashTool.startsWithMkdirPForTest("mkdir -p /tmp/bar"));
        assertTrue(BashTool.startsWithMkdirPForTest("mkdir -pv /tmp/bar"));
        assertTrue(BashTool.startsWithMkdirPForTest("mkdir -vp /tmp/bar"));
        assertTrue(BashTool.startsWithMkdirPForTest("  mkdir -p /tmp/bar"));
        assertTrue(BashTool.startsWithMkdirPForTest("mkdir -p /a /b /c"));
        assertTrue(BashTool.startsWithMkdirPForTest("mkdir -p \"/tmp with space\""));
    }

    @Test
    void doesNotMisidentifyOtherCommands() {
        assertFalse(BashTool.startsWithMkdirPForTest("mkdir /tmp/bar"));
        assertFalse(BashTool.startsWithMkdirPForTest("mkdir -m 755 /tmp/bar"));
        assertFalse(BashTool.startsWithMkdirPForTest("echo mkdir -p /tmp"));
        assertFalse(BashTool.startsWithMkdirPForTest("rm -rf /tmp/bar"));
        assertFalse(BashTool.startsWithMkdirPForTest(""));
    }

    /** End-to-end: a real bash call with `mkdir -p` should
     *  NOT create a literal `-p` folder on Windows. This is
     *  the regression that prompted R326. We sandbox into a
     *  temp dir so the test doesn't touch the user's cwd. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void mkdirDashPDoesNotCreateLiteralDashP() throws java.io.IOException {
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("r326-mkdir-p-");
        try {
            java.io.File cwd = tmp.toFile();
            String cmd = "mkdir -p " + tmp.resolve("a/b/c").toString()
                       + " && dir " + cwd.getAbsolutePath();
            var r = BashTool.runForeground(cmd, cwd, 30_000, false, null);
            // The literal `-p` directory must NOT exist.
            java.io.File literalDashP = new java.io.File(cwd, "-p");
            assertFalse(literalDashP.exists(),
                "R326: 'mkdir -p' must not create a literal '-p' folder. Got: "
                    + literalDashP.getAbsolutePath());
            // The intended target MUST exist.
            java.io.File target = new java.io.File(tmp.toFile(), "a/b/c");
            assertTrue(target.exists(),
                "R326: 'mkdir -p' should have created the target recursively. "
                    + "Target missing: " + target.getAbsolutePath());
        } finally {
            // best-effort cleanup
            java.nio.file.Files.walk(tmp)
                .sorted(java.util.Comparator.reverseOrder())
                .map(java.nio.file.Path::toFile)
                .forEach(f -> { try { java.nio.file.Files.deleteIfExists(f.toPath()); } catch (Exception ignored) {} });
        }
    }
}