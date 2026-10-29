/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.protocol.methods;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R745 (2026-10-21) — the AUDIT half of the consumer-side terminal-event
 * contract, applied to {@link AetherCodeMethods#query}.
 *
 * <h2>What the audit found</h2>
 * The main query path was <em>better</em> than the continuation
 * dispatcher, but not complete. Walking the three questions:
 *
 * <ul>
 *   <li><b>Does it close?</b> — NO. Pre-fix it was
 *       {@code target.query(prompt).forEach(...)}: the stream was never
 *       closed. Now bound in try-with-resources.</li>
 *   <li><b>Is there an "exited but no RunEnd" path?</b> — YES, and this
 *       is the interesting one. {@code forEach} returns normally on
 *       clean exhaustion, so a stream that ends without a RunEnd is
 *       indistinguishable from a healthy one at the point of return.
 *       The renderer then keeps {@code isStreaming=true} forever with
 *       no error banner and no retry affordance — R735's "dead engine
 *       looks like a healthy green Idle", reproduced on the main path.
 *       Now guarded.</li>
 *   <li><b>What happens when the session run lock is held?</b> —
 *       ALREADY CORRECT, and worth pinning because it is easy to break.
 *       The 800ms bounded retry then an explicit
 *       {@code ok:false, error:"session is busy with run <id>",
 *       busyRunId} — a determinate rejection the renderer can surface,
 *       not a silent "accepted". Per the project's standing principle
 *       that determinable logic takes the determinate path, a lock
 *       conflict must never be reported as success.</li>
 * </ul>
 *
 * <p>The R735a outer {@code catch (Throwable)} that synthesises an
 * error RunEnd WAS already present and correct, and the
 * {@code finally} already released the lock on every path. Those are
 * pinned here as regressions rather than rewritten.
 *
 * <p>Source-pin, per R743's reasoning: this code runs on an async RPC
 * worker thread inside a live engine, and the guarantees worth pinning
 * are STRUCTURAL (is it closed, is there a guard, does the busy path
 * reject).
 */
class AetherCodeMethodsConsumerR745Test {

    private static final String MAIN =
            "org/aethercode/protocol/methods/AetherCodeMethods.java";

    private static String sourceOf(String relative) throws Exception {
        Path p = Paths.get("src", "main", "java", relative);
        assertTrue(Files.exists(p),
                "expected source at " + p.toAbsolutePath() + " (surefire working "
                        + "directory is " + Paths.get("").toAbsolutePath() + ")");
        String src = String.join("\n", Files.readAllLines(p, StandardCharsets.UTF_8));
        assertTrue(src.contains("R745"),
                "diagnostic: " + relative + " (len=" + src.length() + ") resolved and was "
                        + "read, but contains no R745 marker — surefire is running against a "
                        + "stale/other copy of this file");
        return src;
    }

    private static String code(String relative) throws Exception {
        return sourceOf(relative)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .lines()
                .map(l -> l.contains("//") ? l.substring(0, l.indexOf("//")) : l)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    // ------------------------------------------------------------------
    //  close
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R745: the main query stream is closed, not left dangling on a bare forEach")
    void queryStreamIsClosed() throws Exception {
        String c = code(MAIN);
        assertTrue(c.contains("target.query(prompt)) {"),
                "R745: target.query(prompt) must be bound in try-with-resources. A bare "
                        + "`target.query(prompt).forEach(...)` never closes the "
                        + "Spliterator-backed stream and leaks the chat-client resources "
                        + "behind it for the life of the JVM.");
        assertTrue(c.contains("stream.forEach(ev -> {"),
                "R745: the consumer body must run off the try-with-resources binding");
    }

    // ------------------------------------------------------------------
    //  terminal-event contract
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R745: a main query that drains without RunEnd still emits a terminal event")
    void exhaustedWithoutRunEndIsTerminal() throws Exception {
        String c = code(MAIN);
        assertTrue(c.contains("if (!sawRunEnd[0])"),
                "R745: forEach returns normally on clean exhaustion, so a stream ending "
                        + "without a RunEnd is indistinguishable from a healthy one at the "
                        + "point of return — the renderer's isStreaming then stays true "
                        + "forever with no error banner (R735's dead-engine-reads-as-Idle). "
                        + "A post-forEach guard is required.");
        assertTrue(c.contains("no_run_end"),
                "R745: the synthesised event must be identifiable as no_run_end");
        assertTrue(c.contains("\"type\", \"run_end\""),
                "R745: the synthetic event must use the run_end shape the renderer's "
                        + "stream_event handler switches on, or the handler won't fire");
    }

    @Test
    @DisplayName("R745: the RunEnd branch records the terminal event (no false positive)")
    void runEndBranchRecordsTerminal() throws Exception {
        String c = code(MAIN);
        assertTrue(c.contains("sawRunEnd[0] = true"),
                "R745: the RunEnd branch must set sawRunEnd, otherwise the guard fires on "
                        + "EVERY healthy run and injects a spurious no_run_end error event "
                        + "after each successful turn.");
    }

    @Test
    @DisplayName("R745: R735a's outer catch still synthesises an error run_end")
    void outerCatchContractPreserved() throws Exception {
        String c = code(MAIN);
        // The R735a contract: a throw escaping the consumer still yields
        // a terminal event. Pinned because R745's try-with-resources
        // re-indents this whole region and a bad edit would silently
        // drop it.
        assertTrue(c.contains("catch (Throwable th)"),
                "R745: the outer catch (Throwable) must survive — it is what converts a throw "
                        + "escaping the stream into a terminal event instead of a dead run");
        // R384: the synthetic error run_end must still be MARKED as a
        // failure, so the renderer's failed-pill bucket (R735b) keeps
        // classifying it as one.
        //
        // This used to be a source-pin on the literal
        // `endEvent.put("isError", true)`. R384 moved the frame
        // assembly into the ONE builder, so that literal is gone — and
        // the pin went red on a change that made the code BETTER.
        // A contract pin must FOLLOW the implementation, so the
        // contract is now asserted BEHAVIOURALLY, through the same
        // builder the daemon uses: the reason the catch terminal
        // reports must produce a frame that is marked as a failure.
        Map<String, Object> frame = AetherCodeMethods.runEndFrame(
                "error: java.lang.IllegalStateException: boom",
                "synthetic");
        assertEquals(Boolean.TRUE, frame.get("isError"),
                "R745: the synthetic error run_end must stay marked isError so the renderer's "
                        + "failed-pill bucket (R735b) keeps classifying it as a failure");
        assertEquals("error", frame.get("stopKind"),
                "R745: the synthetic run_end must carry the engine's canonical kind, so a client "
                        + "never has to re-parse the decorated reason");
    }

    @Test
    @DisplayName("R745: the session lock busy path rejects determinately, never silently accepts")
    void busyLockRejectsDeterminately() throws Exception {
        String c = code(MAIN);
        assertTrue(c.contains("\"session is busy with run \""),
                "R745: when the session run lock cannot be acquired within the retry window, "
                        + "the RPC must return an explicit rejection. Reporting 'accepted' "
                        + "for a run that will never dispatch strands the renderer waiting on "
                        + "stream events that are never coming.");
        assertTrue(c.contains("\"busyRunId\""),
                "R745: the rejection must name the blocking run so the renderer/user can "
                        + "attribute the conflict");
        // The bounded retry must remain bounded — an unbounded spin here
        // would block the RPC worker thread indefinitely.
        assertTrue(c.contains("long deadline = System.currentTimeMillis() + 800"),
                "R745: the busy-wait must stay bounded (800ms). An unbounded spin on the "
                        + "session lock would hang the RPC worker thread.");
    }

    @Test
    @DisplayName("R745: the finally still releases the session lock on every path")
    void finallyReleasesLock() throws Exception {
        String c = code(MAIN);
        assertTrue(c.contains("sessionRunLock.remove(_targetSid, runId)"),
                "R745: the run's finally must release the session run lock (compare-and-remove "
                        + "so a stale holder cannot free a newer owner's lock)");
    }

    @Test
    @DisplayName("R745: the repair branch leaves a LOG marker")
    void repairBranchIsInstrumented() throws Exception {
        assertTrue(code(MAIN).contains("R745: query consumer exited without RunEnd"),
                "R745: the no-RunEnd guard must LOG a marker (Lesson 1052 — an uninstrumented "
                        + "branch cannot be proven deployed, and without it this exact silent "
                        + "failure is invisible after the fact).");
    }
}