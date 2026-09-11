package org.aethercode.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-2-18 acceptance tests for {@link SessionTrashCommand}.
 * Three behaviours: list (default), --empty (with --yes to
 * skip the prompt), and --restore (single-session restore).
 */
class SessionTrashCommandTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream oldOut, oldErr;

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
        SupervisorRpc.resetToDefault();
    }

    @Test
    void listPrintsHeaderAndTrashedRows() throws Exception {
        // Default invocation: list every trashed session.
        SupervisorRpc.installForTesting((method, params) -> {
            assertThat(method).isEqualTo("session/list");
            assertThat(params).containsEntry("trashed", true);
            return Map.of("sessions", List.of(
                    Map.of("id", "c-old1", "title", "Old work", "trashedAt", "2026-08-15T10:00:00Z"),
                    Map.of("id", "c-old2", "title", "Test scratch", "trashedAt", "2026-08-20T11:00:00Z")),
                    "total", 2);
        });
        SessionTrashCommand cmd = new SessionTrashCommand();
        int rc = cmd.call();
        assertThat(rc).isEqualTo(0);
        String stdout = out.toString(StandardCharsets.UTF_8);
        // Header is always printed.
        assertThat(stdout).contains("ID\tTITLE\tTRASHED_AT");
        assertThat(stdout).contains("c-old1").contains("Old work");
        assertThat(stdout).contains("c-old2").contains("Test scratch");
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("2 trashed session(s)");
    }

    @Test
    void emptyWithYesFlagCallsRpcAndPrintsCount() throws Exception {
        // --empty --yes: skip the confirmation prompt and
        // call session/trash with {empty: true}. The RPC
        // returns a deleted count.
        Map<String, Object> seen = new java.util.HashMap<>();
        SupervisorRpc.installForTesting((method, params) -> {
            seen.put("method", method);
            seen.put("params", new java.util.HashMap<>(params));
            return Map.of("ok", true, "deleted", 3);
        });
        SessionTrashCommand cmd = new SessionTrashCommand();
        cmd.empty = true;
        cmd.yes = true;
        int rc = cmd.call();
        assertThat(rc).isEqualTo(0);
        assertThat(seen.get("method")).isEqualTo("session/trash");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) seen.get("params");
        assertThat(params).containsEntry("empty", true);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("emptied Trash")
                .contains("3");
    }

    @Test
    void restoreDelegatesToSessionRestoreRpc() throws Exception {
        // --restore <id> calls session/restore and prints
        // the success ack.
        Map<String, Object> seen = new java.util.HashMap<>();
        SupervisorRpc.installForTesting((method, params) -> {
            seen.putAll(params);
            return Map.of("ok", true);
        });
        SessionTrashCommand cmd = new SessionTrashCommand();
        cmd.restore = "c-old1";
        int rc = cmd.call();
        assertThat(rc).isEqualTo(0);
        assertThat(seen).containsEntry("id", "c-old1");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("restored c-old1");
    }
}
