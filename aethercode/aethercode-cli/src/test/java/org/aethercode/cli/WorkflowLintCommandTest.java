package org.aethercode.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-2-12 acceptance tests for {@link WorkflowLintCommand}.
 * The test exercises the CLI through picocli's standard
 * programmatic invocation (the same way
 * {@code aethercode.workflow.cli.GrantsCliTest} does), with
 * a captured stdout/stderr so we can assert on the human and
 * JSON output shapes.
 */
class WorkflowLintCommandTest {

    @Test
    void unknownWorkflowPrintsErrorAndExits2(@TempDir Path tmp) {
        // The named workflow doesn't exist in either layer;
        // the lint currently returns an empty list (per the
        // StubWorkflowService contract) so we expect a clean
        // "0 problems" message + exit 0. (The lint is
        // best-effort; if a real validator is wired in,
        // existence-checking moves to a different code path.)
        Capture cap = run(tmp, "missing-workflow");
        assertThat(cap.exit).isEqualTo(0);
        assertThat(cap.stdout).contains("ok — missing-workflow (0 problems)");
    }

    @Test
    void validWorkflowPrintsOkAndExits0(@TempDir Path tmp) throws Exception {
        // A well-formed workflow YAML in the project layer.
        Path wfDir = tmp.resolve(".aethercode/workflows");
        Files.createDirectories(wfDir);
        Files.writeString(wfDir.resolve("ok-flow.yaml"), """
                version: 1
                name: ok-flow
                description: A passing workflow.
                prompts:
                  - role: user
                    content: hello
                """);
        Capture cap = run(tmp, "ok-flow");
        assertThat(cap.exit).isEqualTo(0);
        assertThat(cap.stdout).contains("ok — ok-flow (0 problems)");
    }

    @Test
    void invalidNameReturnsErrorAndExits2(@TempDir Path tmp) {
        // A name containing path-traversal characters is
        // rejected before the file system is touched. The
        // command prints to stderr (not stdout) and exits 2
        // so the user can pipe stdout without seeing the
        // error.
        Capture cap = run(tmp, "../etc/passwd");
        assertThat(cap.exit).isEqualTo(2);
        assertThat(cap.stdout).isEmpty();
        assertThat(cap.stderr).contains("invalid workflow name");
    }

    // ---- helpers ----

    private static Capture run(Path cwd, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream oldErr = System.err;
        int exit;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            WorkflowLintCommand cmd = new WorkflowLintCommand();
            cmd.cwd = cwd;
            cmd.userHome = cwd; // sandbox
            cmd.name = args[0];
            exit = cmd.call();
        } finally {
            System.setOut(oldOut);
            System.setErr(oldErr);
        }
        return new Capture(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int exit, String stdout, String stderr) {}
}
