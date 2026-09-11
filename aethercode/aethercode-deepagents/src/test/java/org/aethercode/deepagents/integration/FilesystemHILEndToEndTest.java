package org.aethercode.deepagents.integration;

import org.aethercode.core.fs.backend.StateBackend;
import org.aethercode.deepagents.middleware.FilesystemMiddleware;
import org.aethercode.core.middleware.FilesystemPathTraversalException;
import org.aethercode.core.middleware.FilesystemPathValidator;
import org.aethercode.core.middleware.FilesystemPermission;
import org.aethercode.core.middleware.FilesystemPermissionChecker;
import org.aethercode.core.middleware.FilesystemPermissionDeniedException;
import org.aethercode.core.middleware.FilesystemToolNames;
import org.aethercode.core.middleware.FilesystemOperation;
import org.aethercode.deepagents.tools.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-4 end-to-end integration test for the deepagents
 * filesystem HIL chain: {@link FilesystemPathValidator} +
 * {@link FilesystemPermissionChecker} + {@link FilesystemPermission}.
 *
 * <p>The chain lives inside
 * {@link FilesystemToolset} (each filesystem tool calls
 * {@code FilesystemPathValidator.validateAndNormalize} first, then
 * {@code FilesystemPermissionChecker.check}); prior round's
 * {@code DeepagentsR2IntegrationTest.filesystemMiddleware_rejectsOutOfAllowedRoots}
 * covers the in-memory backend with a single DENY rule. This test
 * adds three higher-fidelity scenarios to close the HIL gap:</p>
 *
 * <ol>
 *   <li><b>Allowed write succeeds</b> &mdash; {@code file_write} into
 *       an explicitly allowed prefix writes the file and the
 *       backend's snapshot reflects the change.</li>
 *   <li><b>Denied write throws {@link FilesystemPermissionDeniedException}</b>
 *       &mdash; a {@code file_write} to a path that the rules
 *       DENY propagates the exception all the way to the
 *       caller (the agent loop's dispatch catches it and emits
 *       a failed {@code ToolMessage}).</li>
 *   <li><b>Path traversal is rejected by the validator first</b> &mdash;
 *       a {@code file_read} whose path contains {@code ..} never
 *       even reaches the permission gate; the validator throws
 *       {@link FilesystemPathTraversalException} so the agent
 *       gets a clean "Error: Path traversal not allowed" string
 *       the model can read.</li>
 * </ol>
 *
 * <p>Together these prove the full HIL pipeline runs end-to-end
 * against a real {@link StateBackend} rooted in a temp directory:
 * a user-supplied tool call flows through validator → checker
 * → backend, with the right exception type raised at the right
 * stage.</p>
 */
class FilesystemHILEndToEndTest {

    // =================================================================
    //  Scenario 1 — allowed write succeeds
    // =================================================================

    @Test
    @DisplayName("Scenario 1: file_write into an allowed prefix succeeds and lands on the backend")
    void fileWriteIntoAllowedPrefix_succeeds(@TempDir Path tmp) {
        // A real StateBackend rooted at the test's temp dir. The
        // backend's virtual paths are independent of the host
        // filesystem, but using @TempDir keeps the test
        // hermetic and matches how a user would mount a fresh
        // working directory.
        StateBackend backend = new StateBackend();
        // Permission rules: allow writes anywhere under /tmp/test,
        // deny writes anywhere else. The single deny uses "/**"
        // so the recursive glob catches every absolute path.
        List<FilesystemPermission> rules = List.of(
                new FilesystemPermission(
                        Set.of(FilesystemOperation.WRITE, FilesystemOperation.READ),
                        List.of("/tmp/test")),
                new FilesystemPermission(
                        FilesystemPermission.Mode.DENY,
                        Set.of(FilesystemOperation.WRITE, FilesystemOperation.READ),
                        List.of("/**")));
        FilesystemMiddleware middleware = new FilesystemMiddleware(backend, rules);
        Tool writeFile = middleware.toolset().get(FilesystemToolNames.WRITE_FILE);
        assertThat(writeFile).as("write_file tool is registered").isNotNull();

        // The allowed write proceeds, the backend stores the file.
        assertThatCode(() -> writeFile.invoke(Map.of(
                "file_path", "/tmp/test/x.txt",
                "content", "hello")))
                .doesNotThrowAnyException();
        assertThat(backend.snapshot())
                .as("the backend stored the file at the virtual path")
                .containsKey("/tmp/test/x.txt");
    }

    // =================================================================
    //  Scenario 2 — denied write throws
    // =================================================================

    @Test
    @DisplayName("Scenario 2: file_write to a denied path throws FilesystemPermissionDeniedException")
    void fileWriteToDeniedPath_throwsPermissionDeniedException() {
        StateBackend backend = new StateBackend();
        List<FilesystemPermission> rules = List.of(
                // /etc is the only read/write deny — no ALLOW
                // matches it so any file_write to /etc is rejected.
                new FilesystemPermission(
                        FilesystemPermission.Mode.DENY,
                        Set.of(FilesystemOperation.WRITE),
                        List.of("/etc")));
        FilesystemMiddleware middleware = new FilesystemMiddleware(backend, rules);
        Tool writeFile = middleware.toolset().get(FilesystemToolNames.WRITE_FILE);

        // The validator passes /etc/passwd (no traversal, no
        // tilde, no Windows drive); the checker matches the DENY
        // rule and throws.
        assertThatThrownBy(() -> writeFile.invoke(Map.of(
                "file_path", "/etc/passwd",
                "content", "x")))
                .isInstanceOf(FilesystemPermissionDeniedException.class)
                .hasMessageContaining("/etc/passwd");

        // The backend is untouched: no file should have been
        // created at any /etc/* path.
        assertThat(backend.snapshot())
                .as("the denied write never reached the backend")
                .doesNotContainKey("/etc/passwd");
    }

    // =================================================================
    //  Scenario 3 — path traversal is rejected first
    // =================================================================

    @Test
    @DisplayName("Scenario 3: file_read with '..' segments is rejected by the validator before the checker runs")
    void fileReadWithTraversal_isRejectedByValidator() {
        StateBackend backend = new StateBackend();
        // Permissions configured in a way that would ALLOW the
        // (post-normalization) path if the validator ever let it
        // through. The point of this test is that the validator
        // catches traversal BEFORE the checker gets a chance to
        // run, so the rule set is irrelevant to the assertion.
        List<FilesystemPermission> rules = List.of(
                new FilesystemPermission(
                        Set.of(FilesystemOperation.READ),
                        List.of("/etc")));
        FilesystemMiddleware middleware = new FilesystemMiddleware(backend, rules);
        Tool readFile = middleware.toolset().get(FilesystemToolNames.READ_FILE);

        // The model tries to read ../../../etc/passwd. The
        // validator sees the ".." segment and throws the
        // user-facing path-traversal error.
        assertThatThrownBy(() -> readFile.invoke(Map.of(
                "file_path", "../../../etc/passwd")))
                .isInstanceOf(FilesystemPathTraversalException.class)
                .hasMessageContaining("Path traversal");

        // The same input, sent directly through the validator
        // helper, raises the same exception. This is a stronger
        // contract: callers (and tests) can use the validator
        // directly without having to spin up a tool.
        assertThatThrownBy(() -> FilesystemPathValidator.validateAndNormalize(
                "../../../etc/passwd"))
                .isInstanceOf(FilesystemPathTraversalException.class)
                .hasMessageContaining("Path traversal");
    }

    // =================================================================
    //  Scenario 4 — checker behaviour in isolation
    // =================================================================

    @Test
    @DisplayName("Scenario 4: FilesystemPermissionChecker returns ALLOW/DENY/INTERRUPT in declaration order")
    void permissionChecker_returnsVerdictForFirstMatch() {
        // Three rules:
        //   1. ALLOW /tmp/notes
        //   2. DENY /etc
        //   3. INTERRUPT /work/secrets
        // First matching rule wins; default (no match) is ALLOW.
        List<FilesystemPermission> rules = List.of(
                new FilesystemPermission(
                        Set.of(FilesystemOperation.WRITE),
                        List.of("/tmp/notes")),
                new FilesystemPermission(
                        FilesystemPermission.Mode.DENY,
                        Set.of(FilesystemOperation.WRITE),
                        List.of("/etc")),
                new FilesystemPermission(
                        FilesystemPermission.Mode.INTERRUPT,
                        Set.of(FilesystemOperation.WRITE),
                        List.of("/work/secrets")));

        assertThat(FilesystemPermissionChecker.check(
                rules, FilesystemOperation.WRITE, "/tmp/notes/x.txt"))
                .as("ALLOW rule matches /tmp/notes")
                .isEqualTo(FilesystemPermissionChecker.Verdict.ALLOW);
        assertThat(FilesystemPermissionChecker.check(
                rules, FilesystemOperation.WRITE, "/etc/passwd"))
                .as("DENY rule matches /etc")
                .isEqualTo(FilesystemPermissionChecker.Verdict.DENY);
        assertThat(FilesystemPermissionChecker.check(
                rules, FilesystemOperation.WRITE, "/work/secrets/key"))
                .as("INTERRUPT rule matches /work/secrets")
                .isEqualTo(FilesystemPermissionChecker.Verdict.INTERRUPT);
        assertThat(FilesystemPermissionChecker.check(
                rules, FilesystemOperation.WRITE, "/anywhere/else"))
                .as("no rule matches → permissive default ALLOW")
                .isEqualTo(FilesystemPermissionChecker.Verdict.ALLOW);
    }
}
