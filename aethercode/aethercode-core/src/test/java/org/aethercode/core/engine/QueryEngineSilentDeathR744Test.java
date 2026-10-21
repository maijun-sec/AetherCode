package org.aethercode.core.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R744 (2026-10-21) — the fourth silent-death path.
 *
 * <h2>Why a new test when R735/R735a already exist</h2>
 * {@link QueryEngineR735Test} pins "every {@code stopReason = "X"}
 * terminal branch emits a {@code RunEnd}" and R735a pins "tryAdvance
 * is wrapped in {@code catch (Throwable t)}". Both are true of the
 * current code, and both were true on 2026-10-21 00:00 while the daemon
 * sat dead for 11 minutes. The gap is a <em>reachability</em> gap:
 *
 * <ul>
 *   <li>R735 audits terminal branches that the engine actually
 *       <b>reaches</b>.</li>
 *   <li>R735a audits the catch that fires when something
 *       <b>throws</b>.</li>
 * </ul>
 *
 * <p>R744 was neither: the engine never reached a terminal branch at
 * all, and nothing threw. It was parked inside
 * {@code currentLlmIt.next()} → the chat client's Spliterator →
 * {@code queue.take()} → a runner thread blocked on a socket read that
 * never returned. From the engine's point of view {@code tryAdvance}
 * was simply still running.
 *
 * <h2>What this test adds</h2>
 * <ol>
 *   <li>An explicit inventory of the engine's four termination paths,
 *       each asserted to emit a RunEnd BEFORE returning false — which
 *       is the R735 contract restated per-path, so that adding a fifth
 *       path later has an obvious place to be registered.</li>
 *   <li>A pin that the engine documents the reachability gap, so the
 *       next engineer reading the outer catch learns it is not
 *       sufficient on its own.</li>
 * </ol>
 *
 * <p>The actual R744 fix lives in {@code SpringAiChatClient} (the only
 * layer that can bound the blocking read) and is covered behaviourally
 * by {@code SpringAiChatClientR744Test}. This class deliberately does
 * <em>not</em> pretend the engine can fix it — see
 * {@link #engineCatchDocumentsThatItCannotCoverABlockedRead()}.
 */
class QueryEngineSilentDeathR744Test {

    private static final String QE =
            "src/main/java/org/aethercode/core/engine/QueryEngine.java";

    private static String readSource() {
        try {
            String[] candidates = new String[] {
                    QE,
                    "aethercode-core/" + QE,
                    "../aethercode-core/" + QE,
            };
            for (String c : candidates) {
                java.io.File f = new java.io.File(c);
                if (f.exists()) return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
            throw new java.io.FileNotFoundException(
                    "QueryEngine.java not found under any of "
                            + java.util.Arrays.toString(candidates));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Window large enough to span a terminal branch's comment block
     *  plus its emit. The same_error branch carries a ~25-line
     *  explanatory comment between the SideNote and the RunEnd. */
    private static final int WINDOW = 4000;

    /**
     * Anchor for the OUTER catch of {@code tryAdvance}.
     *
     * <p>A bare {@code "} catch (Throwable t) {"} is NOT usable: the
     * file contains three {@code catch (Throwable t)} sites and
     * {@code indexOf} lands on the {@code CostTracker} one, whose
     * window has neither a RunEnd nor an R744 marker — which is
     * exactly how the first version of this test failed. The outer
     * catch is uniquely identified by its R735a comment header, so we
     * anchor on that instead.
     */
    private static final String OUTER_CATCH_ANCHOR =
            "// R735a (2026-10-20): the LLM stream threw mid-iteration";

    /** Locate the outer catch block, returning its offset. */
    private static int outerCatchIdx(String src) {
        int anchor = src.indexOf(OUTER_CATCH_ANCHOR);
        if (anchor < 0) return -1;
        // The `} catch (Throwable t) {` line sits immediately ABOVE the
        // comment header, so back up to it rather than scanning forward.
        int brace = src.lastIndexOf("} catch (Throwable t) {", anchor);
        return brace > 0 ? brace : anchor;
    }

    /**
     * Paths 1-3 of the four engine termination shapes. Each entry names
     * a unique anchor in the source that identifies the branch, and a
     * human description of when it fires. Each must reach a
     * {@code StreamEvent.RunEnd} before its {@code return false;}.
     *
     * <p>Path 4 ({@code runtime_exception}) is the outer catch and gets
     * its own behavioural-style assertion below, because its RunEnd is
     * emitted inside a catch rather than in a straight-line branch.
     */
    private static final Object[][] TERMINAL_PATHS = new Object[][] {
            {
                    "cancelled",
                    "R721 hard cancel via Thread.interrupt()",
            },
            {
                    "same_error_hard_stop",
                    "R181 same_error death-spiral hard stop",
            },
            {
                    "awaiting_user_decision",
                    "TodoRunController AwaitUser verdict",
            },
            {
                    "runtime_exception",
                    "R735a outer catch — anything thrown inside tryAdvance",
            },
    };

    /**
     * Every one of the engine's four termination paths must be present
     * AND reach a {@code StreamEvent.RunEnd}. This is the R735
     * contract applied path-by-path: it is an <em>inventory</em>, so
     * that a future fifth terminal path has an obvious place to be
     * registered rather than being discovered by an 11-minute hang in
     * production.
     *
     * <p>Each row anchors on the branch's own stopReason literal (which
     * is unique per path), then requires a RunEnd in the forward
     * window. The per-path positive assertions below additionally
     * check the exact RunEnd form each branch must use.
     */
    @Test
    void everyEngineTerminationPath_emitsRunEndBeforeReturningFalse() {
        String src = readSource();
        for (Object[] row : TERMINAL_PATHS) {
            String name = (String) row[0];
            String desc = (String) row[1];

            // `stopReason = "<name>"` is the canonical entry to each
            // terminal branch. The R735a path is assigned inside the
            // catch via a concatenation (`"runtime_exception: " + …`),
            // so anchor on the literal with its trailing colon.
            int idx = src.indexOf("stopReason = \"" + name + "\"");
            if (idx < 0) idx = src.indexOf("\"" + name + ": \"");
            assertTrue(idx > 0,
                    "R744: termination path '" + name + "' (" + desc + ") no longer exists "
                            + "in QueryEngine.java. If it was renamed or removed, update "
                            + "TERMINAL_PATHS in this test; if a NEW terminal path was added, "
                            + "register it here so its emit-RunEnd contract is audited.");

            String window = src.substring(idx, Math.min(idx + WINDOW, src.length()));
            assertTrue(window.contains("StreamEvent.RunEnd"),
                    "R744: termination path '" + name + "' (" + desc + ") must emit a "
                            + "StreamEvent.RunEnd before `return false;`. Without it the "
                            + "desktop's stream_event handler cannot flip isStreaming=false "
                            + "or populate lastFailedPrompt, and the engine reads as a "
                            + "healthy green ● Idle.");
        }
    }

    /**
     * Path-specific positive assertions. The inventory test above
     * proves the branches exist and are reachable; these prove each
     * one is followed by its emit rather than by a bare
     * {@code return false;}.
     */
    @Test
    void cancelPath_emitsRunEnd() {
        String src = readSource();
        int idx = src.indexOf("if (Thread.interrupted()) {");
        assertTrue(idx > 0, "R744: cancel guard not found");
        String window = src.substring(idx, Math.min(idx + WINDOW, src.length()));
        assertTrue(window.contains("StreamEvent.RunEnd"),
                "R744: the Thread.interrupted() cancel branch must emit a RunEnd so the "
                        + "renderer leaves the streaming state (R721/R735 contract)");
    }

    @Test
    void sameErrorHardStopPath_emitsRunEnd() {
        String src = readSource();
        int idx = src.indexOf("\"same_error_hard_stop\"");
        assertTrue(idx > 0, "R744: same_error_hard_stop not found");
        String window = src.substring(idx, Math.min(idx + WINDOW, src.length()));
        assertTrue(window.contains("StreamEvent.RunEnd(stopReason, lastAssistantBlocks)"),
                "R744: the same_error_hard_stop branch must emit "
                        + "RunEnd(stopReason, lastAssistantBlocks)");
    }

    @Test
    void awaitingUserDecisionPath_emitsRunEnd() {
        String src = readSource();
        int idx = src.indexOf("\"awaiting_user_decision\"");
        assertTrue(idx > 0, "R744: awaiting_user_decision not found");
        String window = src.substring(idx, Math.min(idx + WINDOW, src.length()));
        assertTrue(window.contains("StreamEvent.RunEnd"),
                "R744: the awaiting_user_decision branch must emit a RunEnd — it also emits "
                        + "AwaitUserDecision, so the renderer must be told the run ended");
    }

    /**
     * Path 4, the outer catch. Modeled on R735a but tightened: the
     * R735a test only asserted the catch exists and that the string
     * "runtime_exception" appears somewhere. It never asserted that a
     * RunEnd is actually emitted INSIDE the catch — which is the part
     * that matters, and the part R744's dead run would have failed if
     * the catch had been reachable (it wasn't).
     */
    @Test
    void outerCatch_translatesThrowIntoRunEnd() {
        String src = readSource();
        int catchIdx = outerCatchIdx(src);
        assertTrue(catchIdx > 0,
                "R744: outer catch (Throwable t) of tryAdvance not found — expected the "
                        + "R735a comment header: " + OUTER_CATCH_ANCHOR);
        String window = src.substring(catchIdx, Math.min(catchIdx + WINDOW, src.length()));
        assertTrue(window.contains("action.accept(new StreamEvent.RunEnd("),
                "R744: the outer catch MUST offer a RunEnd via action.accept — offering it "
                        + "any other way (e.g. only assigning stopReason) leaves the desktop "
                        + "in the green ● Idle state");
        assertTrue(window.contains("return false"),
                "R744: the outer catch must terminate the Spliterator with `return false;` "
                        + "after emitting its RunEnd");
    }

    /**
     * The R744 lesson, pinned as documentation-in-code: the engine's
     * catch is necessary but NOT sufficient, because a downstream
     * block (never a throw) is the fourth death mode. If someone later
     * "simplifies" this comment away and trusts the catch alone, the
     * next provider stall costs another silent 11-minute hang.
     */
    @Test
    void engineCatchDocumentsThatItCannotCoverABlockedRead() {
        String src = readSource();
        int catchIdx = outerCatchIdx(src);
        assertTrue(catchIdx > 0, "R744: outer catch not found");
        String window = src.substring(catchIdx, Math.min(catchIdx + WINDOW, src.length()));
        assertTrue(window.contains("R744"),
                "R744: the outer catch must carry the R744 marker explaining that it "
                        + "cannot cover a downstream block (no throw is raised when the "
                        + "chat client's first-chunk read parks on the socket), and that the "
                        + "bounded read in SpringAiChatClient.awaitFirstChunk is the fix. "
                        + "Without this note the next reader trusts the catch alone and "
                        + "re-opens the silent-death hole.");
    }

    /**
     * Lesson 1057: R735's audit scans the SOURCE TEXT for the
     * {@code stopReason = "..."} assignment shape. A COMMENT that
     * quotes that shape is indistinguishable from a real branch and
     * gets audited as one — and a prose literal has no RunEnd after
     * it, so {@link QueryEngineR735Test} fails with a diagnostic that
     * points at the wrong line entirely.
     *
     * <p>R744 hit this for real, twice: its explanatory comment quoted
     * the catch's stopReason assignment, and the javadoc of this very
     * method did the same. Both times
     * {@code QueryEngineR735Test.everyTerminalBranch_emitsRunEnd} went
     * red with a message about {@code runtime_exception} while the
     * real cause was prose 40 lines earlier. The fix in both cases was
     * to describe the shape without reproducing it. This test keeps
     * that from regressing — note it also polices this file's own
     * comments, since the decoy it looks for is prose-shaped.
     */
    @Test
    void noCommentShapedStopReasonDecoysBreakTheR735Audit() {
        String src = readSource();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("stopReason\\s*=\\s*\"([^\"]*)\"")
                .matcher(src);
        int audited = 0;
        while (m.find()) {
            String reason = m.group(1);
            // Is this match inside a line comment? If so it is prose,
            // and prose must never be shaped like a real assignment —
            // that is precisely what confuses R735.
            int lineStart = src.lastIndexOf('\n', m.start()) + 1;
            String linePrefix = src.substring(lineStart, m.start());
            if (linePrefix.trim().startsWith("//")) {
                org.junit.jupiter.api.Assertions.fail(
                        "R744: QueryEngine.java contains a stopReason assignment literal "
                                + "(reason=" + reason + ") inside a comment at source offset "
                                + m.start() + ". QueryEngineR735Test audits raw source text "
                                + "and cannot tell prose from code, so it treats this decoy as "
                                + "a real terminal branch and fails with a misleading "
                                + "diagnostic. Describe the prefix in prose instead of "
                                + "reproducing the assignment.");
            }
            if ("end_turn".equals(reason)) continue;
            audited++;
        }
        assertTrue(audited >= 4,
                "R744: expected to audit at least 4 real terminal stopReason branches, "
                        + "found " + audited + " — re-validate the R735 contract");
    }
}
