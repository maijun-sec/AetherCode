package org.aethercode.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-2-18 acceptance tests for {@link SessionListCommand}.
 * The RPC layer is stubbed via {@link SupervisorRpc#installForTesting}
 * so the tests exercise the command's parsing + rendering
 * logic without booting a real supervisor.
 */
class SessionListCommandTest {

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
        SupervisorRpc.resetToDefault();
    }

    @Test
    void emptyListStillPrintsHeader() throws Exception {
        // The spec promises a header is always printed, even
        // when there are no sessions. Downstream `awk` / `column`
        // scripts depend on this.
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        SupervisorRpc.installForTesting((method, params) -> {
            captured.set(params);
            return Map.of("sessions", List.of(), "total", 0, "limit", 100, "offset", 0);
        });

        SessionListCommand cmd = new SessionListCommand();
        int rc = cmd.call();
        assertThat(rc).isEqualTo(0);
        String stdout = out.toString(StandardCharsets.UTF_8);
        assertThat(stdout).contains("ID\tTITLE\tCWD\tLAST_ACTIVE\tTOKENS\tSTATE");
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("0 session(s)");
        // The handler didn't get any filter params.
        assertThat(captured.get()).isEmpty();
    }

    @Test
    void filtersAreForwardedToRpc() throws Exception {
        // --cwd / --since / --query / --limit / --trashed must
        // be passed to the RPC. The test verifies each one.
        Map<String, Object> seen = new LinkedHashMap<>();
        SupervisorRpc.installForTesting((method, params) -> {
            seen.putAll(params);
            return Map.of("sessions", List.of(), "total", 0);
        });

        SessionListCommand cmd = new SessionListCommand();
        cmd.cwd = "/work/proj";
        cmd.since = 1_700_000_000_000L;
        cmd.query = "login";
        cmd.limit = 25;
        cmd.trashed = true;
        int rc = cmd.call();
        assertThat(rc).isEqualTo(0);
        assertThat(seen).containsEntry("cwd", "/work/proj")
                .containsEntry("since", 1_700_000_000_000L)
                .containsEntry("query", "login")
                .containsEntry("limit", 25)
                .containsEntry("trashed", true);
    }

    // ---- helpers ----

    @SuppressWarnings("unused")
    private static String str(Object o) { return o == null ? "" : o.toString(); }
}
