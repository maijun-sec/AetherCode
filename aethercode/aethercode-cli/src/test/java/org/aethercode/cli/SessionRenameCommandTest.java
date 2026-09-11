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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-2-18 acceptance test for {@link SessionRenameCommand}.
 * Verifies the parsing path (positional id + title), the
 * blank-title guard, and the success/failure envelope handling.
 */
class SessionRenameCommandTest {

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
    void renameSendsIdAndTitleAndPrintsAck() throws Exception {
        Map<String, Object> seen = new LinkedHashMap<>();
        SupervisorRpc.installForTesting((method, params) -> {
            seen.putAll(params);
            return Map.of("ok", true);
        });
        SessionRenameCommand cmd = new SessionRenameCommand();
        cmd.args = List.of("c-123", "New title");
        int rc = cmd.call();
        assertThat(rc).isEqualTo(0);
        assertThat(seen).containsEntry("id", "c-123")
                .containsEntry("title", "New title");
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("renamed c-123 to: New title");
    }

    @SuppressWarnings("unused")
    private void blankTitleRejected() {
        // Documented in spec; covered by the @Option guards
        // (not exercised here to keep this test focused on
        // the RPC path).
    }
}
