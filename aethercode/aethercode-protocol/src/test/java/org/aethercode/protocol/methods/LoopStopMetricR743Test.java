/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.protocol.methods;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R743 (2026-10-20) — the two protocol-layer sinks that classify a
 * finished run must both go through the ONE canonical taxonomy on
 * {@link org.aethercode.core.stream.StreamEvent}.
 *
 * <h2>Root cause — four independent predicates, three wrong</h2>
 * {@code AetherCodeMethods} (main query stream) and
 * {@code EngineContinuationDispatcher} (child-session stream) each
 * classified the terminal {@code RunEnd} twice, with hand-rolled
 * string heuristics:
 *
 * <pre>
 *   // (a) loop-stop METRIC
 *   if (re.stopReason() != null &amp;&amp; re.stopReason().startsWith("loop")) incLoopStop();
 *   // (b) TRACE SPAN status
 *   endSpan(..., re.stopReason() != null &amp;&amp; re.stopReason().startsWith("error") ? "error" : "ok");
 * </pre>
 *
 * Between them they missed every stopReason the ENGINE raises on its
 * own:
 *
 * <ul>
 *   <li>(a) missed {@code same_error_hard_stop} (R181/R739) and
 *       {@code cancelled} (R721) — both do not start with
 *       {@code "loop"}. So {@code incLoopStop()} under-counted
 *       precisely the unattended death spirals it exists to alert on,
 *       and {@code getMetrics} reported a clean session while the
 *       engine was hard-stopping runs.</li>
 *   <li>(b) missed {@code runtime_exception: …} (R735a),
 *       {@code cancelled} (R721) and
 *       {@code same_error_hard_stop} (R181/R739) — none start with
 *       {@code "error"}. So a run that blew up mid-stream left an
 *       {@code "ok"} span and the trace recorder reported a clean
 *       session. This is the same silent-failure class the whole
 *       R735 / R735a / R735b arc has been chasing, in a fourth
 *       place.</li>
 * </ul>
 *
 * <p>R743 replaces all four with {@code re.isDeterministicHardStop()}
 * (metric) and {@code re.bareKind()} (span status), delegating to
 * {@code StreamEvent} so these sinks and
 * {@code TodoContinuationHook} can never drift apart again — the exact
 * failure mode that let four predicates disagree.
 *
 * <p>Source-pin tests: the counter and span status are written deep
 * inside an async RPC stream handler, and standing up a full daemon
 * to observe one counter is disproportionate. The CLASSIFICATION is
 * the thing worth pinning; its behaviour is covered behaviourally in
 * {@code StreamEventStopReasonR743Test}.
 */
class LoopStopMetricR743Test {

    private static final String MAIN =
            "org/aethercode/protocol/methods/AetherCodeMethods.java";
    private static final String CHILD =
            "org/aethercode/protocol/methods/EngineContinuationDispatcher.java";

    private static String sourceOf(String relative) throws Exception {
        Path p = Paths.get("src", "main", "java", relative);
        assertTrue(Files.exists(p),
                "expected source at " + p.toAbsolutePath() + " (surefire working "
                        + "directory is " + Paths.get("").toAbsolutePath() + ")");
        // readAllLines + join, NOT Files.readString: the file is CRLF
        // on Windows and the raw text would carry "\r" into every
        // pattern below.
        String src = String.join("\n",
                Files.readAllLines(p, StandardCharsets.UTF_8));
        assertTrue(src.contains("isDeterministicHardStop") || src.contains("bareKind"),
                "diagnostic: " + relative + " (len=" + src.length()
                        + ") resolved and was read, but contains neither R743 marker — "
                        + "surefire is running against a stale/other copy of this file");
        return src;
    }

    /** Source with ALL comment text removed (block then line), so a
     *  negative assertion can neither be satisfied nor defeated by
     *  the R743 rationale prose that quotes the old predicates. */
    private static String code(String relative) throws Exception {
        return sourceOf(relative)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .lines()
                .map(l -> l.contains("//") ? l.substring(0, l.indexOf("//")) : l)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    @DisplayName("R743: both sinks gate incLoopStop on the canonical predicate")
    void loopStopMetricUsesCanonicalPredicate() throws Exception {
        for (String f : new String[]{MAIN, CHILD}) {
            String c = code(f);
            assertTrue(c.contains("if (re.isDeterministicHardStop()) {"),
                    f + ": the loop-stop metric must delegate to "
                            + "StreamEvent.RunEnd.isDeterministicHardStop() so it "
                            + "cannot miss same_error_hard_stop / cancelled");
            assertTrue(c.contains("incLoopStop()"),
                    f + ": the loop-stop counter must still be wired");
            assertFalse(c.contains("startsWith(\"loop\")"),
                    f + ": the old startsWith(\"loop\") metric predicate must be "
                            + "gone — it missed same_error_hard_stop and cancelled, "
                            + "so incLoopStop() under-counted exactly the unattended "
                            + "stops it exists to alert on");
        }
    }

    @Test
    @DisplayName("R743: the trace span status no longer records engine hard-stops as 'ok'")
    void spanStatusNoLongerRecordsHardStopsAsOk() throws Exception {
        for (String f : new String[]{MAIN, CHILD}) {
            String c = code(f);
            assertFalse(c.contains("re.stopReason().startsWith(\"error\")"),
                    f + ": the span-status predicate must not be a bare "
                            + "startsWith(\"error\") — it records runtime_exception, "
                            + "cancelled and same_error_hard_stop spans as \"ok\"");
            assertTrue(c.contains("re.bareKind()"),
                    f + ": the span status must classify via "
                            + "StreamEvent.RunEnd.bareKind() so it agrees with the "
                            + "metric taxonomy");
        }
    }

    @Test
    @DisplayName("R743: no protocol layer re-implements the hard-stop string list")
    void noDuplicateStringLists() throws Exception {
        // The canonical list now lives ONLY in StreamEvent. A future
        // round re-adding a hand-rolled copy in either sink shows up
        // here.
        //
        // NOTE: "cancelled" is deliberately NOT asserted — it is a
        // legitimate JSON-RPC response key elsewhere in these files
        // (`{"cancelled": true, "runId": …}` on the cancel RPC), so
        // its presence says nothing about the loop taxonomy. The two
        // loop kinds below have no other legitimate use.
        for (String f : new String[]{MAIN, CHILD}) {
            for (String line : code(f).split("\n")) {
                assertFalse(line.contains("\"same_error_hard_stop\""),
                        f + " must not hardcode the hard-stop kinds; import the "
                                + "predicate from StreamEvent so the list has one home. "
                                + "Offending line: " + line.trim());
                assertFalse(line.contains("\"loop_detected\""),
                        f + " must not hardcode the hard-stop kinds; import the "
                                + "predicate from StreamEvent. Offending line: " + line.trim());
            }
        }
    }
}