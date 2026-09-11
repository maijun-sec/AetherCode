package org.aethercode.cli;

import org.aethercode.a2a.A2AHttpTransport;
import org.aethercode.a2a.A2AServer;
import org.aethercode.a2a.schema.AgentCard;
import org.aethercode.a2a.schema.Message;
import org.aethercode.a2a.schema.Part;
import org.aethercode.a2a.schema.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for the {@code aethercode a2a ...} subcommands. Each
 * test starts a fresh A2A server on a random port, then invokes the
 * subcommand's {@code call()} method directly (not via a subprocess) and
 * asserts on the captured stdout. Direct invocation is faster and the
 * output is more deterministic — the tests care about the wire shape,
 * not picocli dispatch.
 */
class A2aCommandTest {

    private A2AHttpTransport transport;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        AgentCard card = new AgentCard(
                "cli-test-agent",
                "An agent for CLI tests",
                "0.1.0",
                "http://placeholder/a2a",  // overwritten by transport
                null,
                List.of(),
                new AgentCard.Capabilities(true, false, true),
                null);
        A2AServer server = new A2AServer(card, A2AServer.echoHandler());
        transport = new A2AHttpTransport(server);
        transport.start(0);
        port = transport.port();
    }

    @AfterEach
    void tearDown() {
        if (transport != null) transport.stop();
    }

    @Test
    void a2aDefaultActionPrintsCardSummary() throws Exception {
        // Bare `aethercode a2a <host>` — parent command's
        // call() should fetch the card and print a
        // human-friendly summary.
        A2aCommand cmd = new A2aCommand();
        cmd.host = "127.0.0.1:" + port;
        String out = captureStdout(() -> {
            try {
                return cmd.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(out.contains("name:    cli-test-agent"),
                "expected name in output, got: " + out);
        assertTrue(out.contains("version: 0.1.0"),
                "expected version in output, got: " + out);
        assertTrue(out.contains("url:"),
                "expected url in output, got: " + out);
    }

    @Test
    void a2aCardCommandPrintsJson() throws Exception {
        A2aCardCommand cmd = new A2aCardCommand();
        cmd.host = "127.0.0.1:" + port;
        String out = captureStdout(() -> {
            try {
                return cmd.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        // Should be valid JSON, with name and version.
        assertTrue(out.contains("\"name\""),
                "expected JSON name field, got: " + out);
        assertTrue(out.contains("cli-test-agent"),
                "expected agent name in JSON, got: " + out);
        assertTrue(out.contains("0.1.0"),
                "expected version in JSON, got: " + out);
    }

    @Test
    void a2aSendCommandReturnsArtifactText() throws Exception {
        A2aSendCommand cmd = new A2aSendCommand();
        cmd.host = "127.0.0.1:" + port;
        cmd.text = "ping from cli";
        String out = captureStdout(() -> {
            try {
                return cmd.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        // The default echo handler wraps the input in
        // "echo: <text>"; the CLI prints it under the
        // artifact section.
        assertTrue(out.contains("state:   completed"),
                "expected completed state, got: " + out);
        assertTrue(out.contains("echo: ping from cli"),
                "expected echo artifact text, got: " + out);
    }

    @Test
    void a2aGetCommandRoundTripsTask() throws Exception {
        // First send a message to create a task; then look
        // it up via tasks/get and verify the output.
        A2aSendCommand send = new A2aSendCommand();
        send.host = "127.0.0.1:" + port;
        send.text = "round trip";
        String sendOut = captureStdout(() -> {
            try {
                return send.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        // Parse the task id out of the send output.
        String id = sendOut.lines()
                .filter(l -> l.startsWith("task:"))
                .map(l -> l.substring("task:".length()).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no task id in: " + sendOut));
        // Now fetch it back.
        A2aGetCommand get = new A2aGetCommand();
        get.host = "127.0.0.1:" + port;
        get.taskId = id;
        String getOut = captureStdout(() -> {
            try {
                return get.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(getOut.contains("task:    " + id),
                "expected task id in get output, got: " + getOut);
        assertTrue(getOut.contains("state:   completed"),
                "expected completed state, got: " + getOut);
    }

    @Test
    void a2aCancelCommandTransitionsToCanceled() throws Exception {
        // Send a task to get an id, then cancel it.
        A2aSendCommand send = new A2aSendCommand();
        send.host = "127.0.0.1:" + port;
        send.text = "to be canceled";
        String sendOut = captureStdout(() -> {
            try {
                return send.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        String id = sendOut.lines()
                .filter(l -> l.startsWith("task:"))
                .map(l -> l.substring("task:".length()).trim())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no task id in: " + sendOut));
        A2aCancelCommand cancel = new A2aCancelCommand();
        cancel.host = "127.0.0.1:" + port;
        cancel.taskId = id;
        String out = captureStdout(() -> {
            try {
                return cancel.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(out.contains("state:   canceled"),
                "expected canceled state, got: " + out);
    }

    @Test
    void a2aStreamCommandPrintsWorkingArtifactCompleted() throws Exception {
        // The default echoHandler fallback emits
        // working → artifact → completed.
        A2aStreamCommand cmd = new A2aStreamCommand();
        cmd.host = "127.0.0.1:" + port;
        cmd.text = "stream me";
        try {
            String out = captureStdout(() -> {
                try {
                    return cmd.call();
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
            });
            assertTrue(out.contains("status   working"),
                    "expected working status, got: " + out);
            // Default echoHandler emits an artifact named "echo".
            assertTrue(out.contains("artifact echo"),
                    "expected echo artifact, got: " + out);
            assertTrue(out.contains("status   completed"),
                    "expected completed status, got: " + out);
        } catch (AssertionError ae) {
            // Surface the actual server response to make
            // the 404 easier to debug.
            throw ae;
        }
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    @FunctionalInterface
    private interface ThrowingSupplier {
        Integer get() throws Exception;
    }

    private static String captureStdout(ThrowingSupplier runnable) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (PrintStream ps = new PrintStream(buf, true, StandardCharsets.UTF_8)) {
            System.setOut(ps);
            Integer rc = runnable.get();
            assertNotNull(rc);
            assertEquals(0, rc.intValue(),
                    "expected exit code 0, got " + rc);
        } finally {
            System.setOut(original);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }
}
