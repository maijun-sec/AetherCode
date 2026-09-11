package org.aethercode.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-2-17 acceptance tests for the five {@code ac workflow
 * <subcommand>} subcommands wired up by Java-A:
 * {@link WorkflowListCommand}, {@link WorkflowShowCommand},
 * {@link WorkflowRunCommand}, {@link WorkflowNewCommand},
 * {@link WorkflowEditCommand}. The tests drive picocli
 * programmatically (the same pattern the existing
 * {@code WorkflowLintCommandTest} uses) and capture stdout
 * / stderr so we can assert on the human-readable output.
 *
 * <p>Each test runs the subcommand via
 * {@link CommandLine#execute(String...)} so the user-facing
 * wiring is the same as the user typing
 * {@code ac workflow <subcommand> <args>}.
 */
class WorkflowCommandTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream oldOut;
    private PrintStream oldErr;

    @BeforeEach
    void setUp() {
        oldOut = System.out;
        oldErr = System.err;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(oldOut);
        System.setErr(oldErr);
    }

    // ---- list ---------------------------------------------------------

    @Test
    void listPrintsHeaderAndRows(@TempDir Path cwd) throws Exception {
        // One project-level workflow. The list should show it
        // with a project> marker, in alphabetical order.
        Path wfDir = cwd.resolve(".aethercode").resolve("workflows");
        Files.createDirectories(wfDir);
        Files.writeString(wfDir.resolve("tdd.yaml"), """
                version: 1
                name: tdd
                description: tdd loop
                prompts:
                  - role: user
                    content: go
                """);
        int rc = new CommandLine(new WorkflowListCommand())
                .execute("--cwd", cwd.toString());
        assertThat(rc).isEqualTo(0);
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("project>");
        assertThat(stdout).contains("tdd");
    }

    // ---- show ---------------------------------------------------------

    @Test
    void showPrintsYamlBodyAndHeader(@TempDir Path cwd) throws Exception {
        Path wfDir = cwd.resolve(".aethercode").resolve("workflows");
        Files.createDirectories(wfDir);
        Files.writeString(wfDir.resolve("hello.yaml"), """
                version: 1
                name: hello
                description: A friendly wave.
                prompts:
                  - role: user
                    content: hi
                """);
        int rc = new CommandLine(new WorkflowShowCommand())
                .execute("--cwd", cwd.toString(), "hello");
        assertThat(rc).isEqualTo(0);
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("name:");
        assertThat(stdout).contains("hello");
        assertThat(stdout).contains("--- yaml ---");
    }

    // ---- run ----------------------------------------------------------

    @Test
    void runWithoutEngineSurfacesAnError(@TempDir Path cwd) throws Exception {
        // The CLI currently uses the StubWorkflowService which
        // doesn't run workflows; the command must surface that
        // as a non-zero exit + a helpful stderr line.
        int rc = new CommandLine(new WorkflowRunCommand())
                .execute("--cwd", cwd.toString(), "does-not-exist");
        assertThat(rc).isNotEqualTo(0);
        String stderr = err.toString(StandardCharsets.UTF_8);
        assertThat(stderr).isNotBlank();
    }

    // ---- new ----------------------------------------------------------

    @Test
    void newWritesStarterYaml(@TempDir Path cwd) throws Exception {
        int rc = new CommandLine(new WorkflowNewCommand())
                .execute("--cwd", cwd.toString(), "fresh-flow");
        assertThat(rc).isEqualTo(0);
        Path written = cwd.resolve(".aethercode").resolve("workflows").resolve("fresh-flow.yaml");
        assertThat(written).exists();
        String body = Files.readString(written, StandardCharsets.UTF_8);
        assertThat(body).contains("version: 1");
        assertThat(body).contains("name: fresh-flow");
        assertThat(body).contains("prompts:");
    }

    // ---- edit ---------------------------------------------------------

    @Test
    void editPrintsYamlAndPathHint(@TempDir Path cwd) throws Exception {
        Path wfDir = cwd.resolve(".aethercode").resolve("workflows");
        Files.createDirectories(wfDir);
        Files.writeString(wfDir.resolve("edit-me.yaml"), """
                version: 1
                name: edit-me
                description: to be edited
                prompts:
                  - role: user
                    content: hi
                """);
        int rc = new CommandLine(new WorkflowEditCommand())
                .execute("--cwd", cwd.toString(), "edit-me");
        assertThat(rc).isEqualTo(0);
        String stdout = out.toString(StandardCharsets.UTF_8);
        String stderr = err.toString(StandardCharsets.UTF_8);
        // The body lands on stdout (so the user can pipe it
        // to their editor of choice).
        assertThat(stdout).contains("name: edit-me");
        // A hint with the on-disk path lands on stderr.
        assertThat(stderr).contains("edit me:");
        assertThat(stderr).contains("edit-me.yaml");
    }
}
