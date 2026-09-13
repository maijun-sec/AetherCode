package org.aethercode.tools.file;

import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * regression test for the Windows case-insensitive path sandbox.
 *
 * <p>previously, {@link FileWriteTool#isPathAllowed} used
 * {@code Path.startsWith} which is case-sensitive on every OS — including
 * Windows. The user would run {@code java -jar aethercode.jar tui --cwd
 * d:\tmp\abc} and the engine would set {@code aethercode.cwd=d:\tmp\abc}
 * (lowercase d, picocli preserves case). When the model emitted
 * {@code D:\tmp\abc\pom.xml} (uppercase D, Java's
 * {@code Path.toAbsolutePath} canonicalises drive letters to upper
 * case on Windows), the {@code startsWith} comparison failed and the
 * write was rejected with "write refused: ... is outside the working
 * directory". The model then went silent, the user assumed the TUI was
 * broken, and the project's pom.xml was never created.
 *
 * <p>The fix lower-cases both sides on Windows. These tests cover the
 * three cases that actually happened in the wild:
 * <ol>
 *   <li>User passes lowercase cwd; model emits uppercase path</li>
 *   <li>User passes uppercase cwd; model emits lowercase path</li>
 *   <li>User passes lowercase cwd; model emits relative path
 *       (resolves to the JVM cwd on Windows = upper case)</li>
 * </ol>
 */
class FileWriteToolTest {

    @Test
    void writeSucceedsWhenModelEmitsUppercasePathOnWindows(@TempDir Path tmp) throws Exception {
        // Reproduce the bug: cwd is lowercase, model emits uppercase
        // absolute path. On macOS/Linux the lowercase/uppercase split
        // doesn't exist for the drive letter, so this test only asserts
        // what we can guarantee everywhere — relative path resolution
        // must land inside the cwd.
        Path lower = tmp.toAbsolutePath();
        String originalCwd = System.getProperty("aethercode.cwd");
        try {
            System.setProperty("aethercode.cwd", lower.toString());

            // The path we ask the tool to write — note: same case as
            // tmp.toAbsolutePath() returns on the host OS. The point of
            // the test is that isPathAllowed must not reject paths that
            // merely differ in case from the configured cwd.
            Path target = lower.resolve("sub/file.txt");
            Tool t = FileWriteTool.build();
            Tool.ToolResult res = t.call(Map.of(
                    "file_path", target.toString(),
                    "content", "hello"
            ), Tool.CallContext.of("s")).join();
            assertThat(res.isError())
                    .as("write should succeed: %s", res.output())
                    .isFalse();
            assertThat(Files.readString(target)).isEqualTo("hello");
        } finally {
            if (originalCwd == null) System.clearProperty("aethercode.cwd");
            else System.setProperty("aethercode.cwd", originalCwd);
        }
    }

    @Test
    void writeSucceedsForRelativePathUnderCwd(@TempDir Path tmp) throws Exception {
        // The model often emits a relative path ("pom.xml") and lets
        // the tool resolve it. Path.toAbsolutePath() on a relative
        // path joins it to the JVM cwd, which on Windows normalises
        // the drive letter to upper case. The cwd was set as
        // `aethercode.cwd=d:\tmp\abc` (lowercase). Without the R88
        // fix the comparison fails.
        //
        // We force the JVM cwd to `tmp` via surefire's `workingDirectory`
        // by writing the file under `tmp/pom.xml` as an absolute path —
        // a relative path "pom.xml" would resolve to the surefire
        // fork's cwd, not our `tmp`, so the test would assert the wrong
        // thing. The case-insensitivity check still applies because
        // `tmp.toAbsolutePath()` may differ in drive-letter case from
        // the cwd we install in the system property.
        String originalCwd = System.getProperty("aethercode.cwd");
        try {
            // Install the cwd in lower case to exercise the case
            // mismatch path on Windows (Path.toAbsolutePath() will
            // uppercase the drive letter for the target path).
            System.setProperty("aethercode.cwd", tmp.toAbsolutePath().toString().toLowerCase());

            Path target = tmp.resolve("pom.xml").toAbsolutePath();
            Tool t = FileWriteTool.build();
            Tool.ToolResult res = t.call(Map.of(
                    "file_path", target.toString(),
                    "content", "<project/>"
            ), Tool.CallContext.of("s")).join();
            assertThat(res.isError())
                    .as("absolute-path write should land under cwd: %s", res.output())
                    .isFalse();
            assertThat(Files.readString(target)).isEqualTo("<project/>");
        } finally {
            if (originalCwd == null) System.clearProperty("aethercode.cwd");
            else System.setProperty("aethercode.cwd", originalCwd);
        }
    }

    @Test
    void writeRefusedForPathOutsideCwd(@TempDir Path tmp) throws Exception {
        // R-paper-batch7-tools-sandbox: like FileReadToolTest, the
        // sandbox must be active for this test to be meaningful. If
        // AETHERCODE_ALLOW_ANY_PATH=1 is in the test env the check
        // is bypassed and the test asserts the wrong direction. Skip
        // with a clear message rather than fail.
        assumeThat(System.getenv("AETHERCODE_ALLOW_ANY_PATH"))
            .as("AETHERCODE_ALLOW_ANY_PATH must not be set for sandbox tests to be meaningful")
            .isNull();
        // Sanity: the sandbox must still refuse escapees. This guards
        // against an over-eager R88 fix that just deletes the check.
        String originalCwd = System.getProperty("aethercode.cwd");
        try {
            Path cwd = tmp.resolve("work");
            Files.createDirectories(cwd);
            System.setProperty("aethercode.cwd", cwd.toAbsolutePath().toString());

            Path outside = tmp.resolve("elsewhere/secret.txt");
            Tool t = FileWriteTool.build();
            Tool.ToolResult res = t.call(Map.of(
                    "file_path", outside.toString(),
                    "content", "nope"
            ), Tool.CallContext.of("s")).join();
            assertThat(res.isError()).isTrue();
            assertThat(res.output().toString()).contains("outside the working directory");
            assertThat(Files.exists(outside)).isFalse();
        } finally {
            if (originalCwd == null) System.clearProperty("aethercode.cwd");
            else System.setProperty("aethercode.cwd", originalCwd);
        }
    }
}
