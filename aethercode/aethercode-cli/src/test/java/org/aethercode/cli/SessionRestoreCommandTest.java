package org.aethercode.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-2-18 acceptance test for {@link SessionRestoreCommand}.
 * Verifies the RPC envelope and the success print.
 */
class SessionRestoreCommandTest {

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
    void restoreSendsIdAndPrintsAck() throws Exception {
        Map<String, Object> seen = new java.util.HashMap<>();
        SupervisorRpc.installForTesting((method, params) -> {
            seen.putAll(params);
            return Map.of("ok", true);
        });
        SessionRestoreCommand cmd = new SessionRestoreCommand();
        cmd.id = "c-123";
        int rc = cmd.call();
        assertThat(rc).isEqualTo(0);
        assertThat(seen).containsEntry("id", "c-123");
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("restored c-123");
    }
}
