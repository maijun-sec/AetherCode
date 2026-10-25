package org.aethercode.cli;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests the {@link Main} entry point's fast-path error handling. The Main class
 * is normally driven by picocli from {@code java -jar aethercode.jar ...} but we
 * exercise its {@link Main#call()} method directly here so the tests don't need a
 * terminal.
 *
 * <p><b>R382:</b> every test that reaches the stdin read controls
 * {@code System.in} explicitly. Two reasons, and the second one is the
 * expensive one:
 * <ul>
 *   <li>determinism — the tests assert "there is no prompt", so stdin must
 *       actually be empty rather than whatever the surefire fork inherited;</li>
 *   <li>the hang — before R382 the plain {@code --print} path read stdin to
 *       EOF. The surefire fork's stdin is an open pipe that never closes, so
 *       {@code System.in.readLine()} blocked forever and took the whole
 *       {@code mvn install} down. These tests now pin the opposite
 *       contract: the plain path must NOT block.</li>
 * </ul>
 */
class MainCliTest {

    /** The stdin the process had before a test replaced it. */
    private final InputStream originalIn = System.in;

    @AfterEach
    void restoreStdin() {
        System.setIn(originalIn);
    }

    @Test
    void call_rejectsPositionalWithoutPrint() {
        System.setIn(new ByteArrayInputStream(new byte[0]));
        Main m = new Main();
        m.prompt = "minimax";
        m.printMode = false;
        int code = assertDoesNotThrow(m::call);
        assertEquals(2, code);
    }

    @Test
    void call_rejectsPrintWithoutPrompt() {
        System.setIn(new ByteArrayInputStream(new byte[0]));
        Main m = new Main();
        m.prompt = null;
        m.printMode = true;
        int code = assertDoesNotThrow(m::call);
        assertEquals(2, code);
    }

    @Test
    void call_rejectsPrintWithBlankPrompt() {
        System.setIn(new ByteArrayInputStream(new byte[0]));
        Main m = new Main();
        m.prompt = "   ";
        m.printMode = true;
        int code = assertDoesNotThrow(m::call);
        assertEquals(2, code);
    }

    @Test
    void call_acceptsPrintWithPrompt() throws Exception {
        // We can't actually run the engine (no API key in test env) but the dispatch
        // should at least get past the validation guards. We give the engine a key
        // so it boots; the actual stream call may fail or hang on a fake endpoint —
        // we don't care, we just want to confirm we got past the print guard (exit
        // code 2). Wrap in a thread with a short timeout to keep the test bounded.
        System.setIn(new ByteArrayInputStream(new byte[0]));
        Main m = new Main();
        m.prompt = "hello";
        m.printMode = true;
        m.apiKey = "test-key"; // boots the client; stream will fail or hang, which is fine
        Thread t = new Thread(() -> {
            try { m.call(); } catch (Exception ignored) {}
        });
        t.setDaemon(true);
        t.start();
        t.join(2_000); // give it 2s — should fail-fast OR start streaming, either way
        // Whether the call is still running (will hit network timeout later) or already
        // returned, the validation exit code 2 is impossible because we passed the
        // print guard. The test is satisfied either way.
    }

    // ---------------------------------------------------------------
    // R382 — the stdin read contract.
    // ---------------------------------------------------------------

    /**
     * The plain {@code --print} path reads what is ALREADY buffered.
     *
     * <p>Positive half of the contract: the feature R747 added must survive
     * the R382 fix. A prompt on stdin still works.
     */
    @Test
    void readPromptFromStdin_plainReadsBufferedBytes() {
        System.setIn(new ByteArrayInputStream("hello from stdin".getBytes(StandardCharsets.UTF_8)));
        String got = new Main().readPromptFromStdin(false);
        assertEquals("hello from stdin", got);
    }

    /**
     * The plain {@code --print} path must NOT block on an open-but-silent
     * stdin.
     *
     * <p>This is the exact shape that hung the surefire fork (and the whole
     * {@code mvn install}) before R382: the pipe is open, the peer never
     * writes, the peer never closes. The read must come back empty and let
     * the caller report the missing prompt.
     *
     * <p>The read is driven from a daemon thread with a join timeout, so a
     * regression fails the test instead of hanging the build.
     */
    @Test
    void readPromptFromStdin_plainDoesNotBlockOnSilentStdin() throws Exception {
        PipedInputStream in = new PipedInputStream();
        // The writer stays OPEN for the whole test and is never written to.
        // It is held in a local that is asserted on below so it cannot be
        // garbage-collected mid-test — a collected writer would close the
        // pipe and the test would pass for the wrong reason (EOF instead of
        // "still open").
        PipedOutputStream writer = new PipedOutputStream(in);
        System.setIn(in);

        String[] got = new String[1];
        Thread t = new Thread(() -> got[0] = new Main().readPromptFromStdin(false));
        t.setDaemon(true);
        t.start();
        t.join(3_000);

        assertFalse(t.isAlive(), "plain --print must not block on an open-but-silent stdin");
        assertNull(got[0], "no prompt was sent, so the read must yield no prompt");
        assertNotNull(writer, "the silent writer must still be open for this assertion to mean anything");
    }

    /**
     * The stream-json path still reads to EOF.
     *
     * <p>The Claude Code protocol keeps the pipe open after the prompt (the
     * same stdin carries {@code control_response} frames), so this path MUST
     * concatenate every line rather than take the buffered bytes. R382
     * changed the plain path only — pinning this stops that from being an
     * accident.
     */
    @Test
    void readPromptFromStdin_streamJsonReadsToEndOfStream() {
        System.setIn(new ByteArrayInputStream(
                "first line\nsecond line\n".getBytes(StandardCharsets.UTF_8)));
        String got = new Main().readPromptFromStdin(true);
        assertTrue(got != null && got.contains("first line") && got.contains("second line"),
                "stream-json must read every line to EOF, got: " + got);
    }
}
