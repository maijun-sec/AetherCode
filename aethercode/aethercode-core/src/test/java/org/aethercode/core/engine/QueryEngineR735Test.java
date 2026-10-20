package org.aethercode.core.engine;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R735 (2026-10-20) — terminal branches in {@link QueryEngine}'s
 * main {@code tryAdvance} body MUST all emit {@code
 * StreamEvent.RunEnd}. The {@code same_error_hard_stop} branch
 * (added in R181) shipped without the emit, so the desktop's
 * stream_event handler never saw a run_end for that path, the
 * engine fell into the green {@code ● Idle} state without an
 * error banner, and the user had no recovery affordance (no ↻
 * retry, no awaiting-decision dialog, nothing).
 *
 * <p>The other three terminal branches in the same body
 * (cancelled, awaiting_user_decision, empty_tool_input) DO
 * emit RunEnd. This test pins the contract on all four by
 * source-scanning the file: a regression that drops the emit
 * on any of the four stopReason flavours surfaces immediately.
 *
 * <p>This is a static-analysis regression test — much cheaper
 * than a full Stream&lt;StreamEvent&gt; integration that would
 * have to wire a stub ChatClient + stub StreamingToolExecutor
 * + a ProgressLoopDetector forced to the same_error tier. The
 * full-stack test would also break every time the engine's
 * internal Spliterator grew a new field. The source-pin keeps
 * the guarantee narrow and stable.
 */
class QueryEngineR735Test {

    private static final String QE = "src/main/java/org/aethercode/core/engine/QueryEngine.java";
    private static String readSource() {
        try {
            // The Maven test cwd is the aethercode-core module root,
            // so QE is a relative path from there. We try both the
            // relative path and a classpath-resolved path so the
            // test works whether `mvn test` is run from
            // aethercode/ or aethercode/aethercode-core/.
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
                    "QueryEngine.java not found under any of " + java.util.Arrays.toString(candidates));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** A canonical terminal branch is
     *  <pre>
     *    stopReason = "...";
     *    finished = true;
     *    [possibly: action.accept(new StreamEvent.SideNote(...))]
     *    [possibly: action.accept(new StreamEvent.RunEnd(...))]
     *    return false;   // or `return true;`
     *  </pre>
     *  We assert that every `stopReason = "X"; finished = true;`
     *  block is followed (within ~12 lines) by an
     *  `action.accept(new StreamEvent.RunEnd(...))`. */
    @Test
    void everyTerminalBranch_emitsRunEnd() {
        String src = readSource();
        // Find every `stopReason = "X..."` assignment. The literal
        // may be a plain string ("cancelled", "same_error_hard_stop")
        // or a concatenation whose head is a plain string with a
        // detail suffix (e.g. `"loop_detected: empty_tool_input (" +
        // n + " consecutive empty tool calls)"`). We capture the
        // whole head literal (up to the first unescaped quote) so
        // both shapes are audited.
        Pattern p = Pattern.compile(
                "stopReason\\s*=\\s*\"([^\"]*)\"",
                Pattern.MULTILINE);
        Matcher m = p.matcher(src);
        int checked = 0;
        while (m.find()) {
            String reason = m.group(1);
            // Skip the per-turn "end_turn" reset at line 1536 / 1708 —
            // that's a default-state seed, not a terminal branch.
            if ("end_turn".equals(reason)) continue;
            int after = m.end();
            String window = src.substring(after, Math.min(after + 1200, src.length()));
            boolean hasRunEnd = window.contains("StreamEvent.RunEnd");
            assertTrue(hasRunEnd,
                    "R735: stopReason=\"" + reason + "\" terminal branch must emit " +
                    "StreamEvent.RunEnd before `return false;` — otherwise the desktop's " +
                    "stream_event handler cannot flip isStreaming=false, populate " +
                    "lastFailedPrompt, or clear currentQuery, leaving the engine in " +
                    "the green ● Idle state with no error banner or recovery affordance.");
            checked++;
        }
        assertTrue(checked >= 4,
                "expected to audit at least 4 terminal stopReason flavours; found " +
                checked + " — has the engine been refactored? Re-validate the R735 contract.");
    }

    @Test
    void sameErrorHardStop_branchCarriesLastAssistantBlocks() {
        String src = readSource();
        // Anchor on the R181 hard-stop SideNote (which names the
        // branch) rather than the stopReason literal — the
        // literal also appears in a LOG.warn message string, so
        // anchoring there can land on the wrong offset.
        int idx = src.indexOf("\"same_error_hard_stop\"");
        assertTrue(idx > 0, "R735: same_error_hard_stop SideNote literal not found in QueryEngine.java");
        // 4000 chars: the R735 fix carries a ~20-line explanatory
        // comment block between the SideNote literal and the
        // RunEnd emit. A tighter window truncates before the
        // emit and produces a false negative.
        String window = src.substring(idx, Math.min(idx + 4000, src.length()));
        assertTrue(window.contains("StreamEvent.RunEnd(stopReason, lastAssistantBlocks)"),
                "R735: same_error_hard_stop branch must emit RunEnd(stopReason, lastAssistantBlocks) " +
                "so the desktop's MessageList can mark the last assistant message complete. " +
                "Pre-R735 the branch had no RunEnd at all; do not regress to `List.of()`.");
    }

    @Test
    void tryAdvance_hasOuterTryCatchRuntimeException() {
            // R735a (2026-10-20): the LLM stream can throw mid-iteration
            // (e.g. upstream 500 from the chat provider that escaped the
            // chat-client retry loop, or any WebClientResponseException
            // that the reactive pipeline propagates). Pre-R735a, the
            // throw escaped tryAdvance and killed the Spliterator with
            // no terminal RunEnd emitted — the desktop sat in the
            // green `● Idle` state with no error banner / no retry
            // affordance, and the user thought the engine was still
            // running. The fix wraps the entire tryAdvance body in
            // try/catch and translates the throw into a single
            // RunEnd with stopReason="runtime_exception: ...".
            //
            // This test guards the contract: tryAdvance must contain
            // a `try {` immediately after the opening brace and a
            // `} catch (Throwable t) {` somewhere in its body. A
            // future refactor that strips the safety net (e.g. to
            // "let the spliterator die naturally on chat errors") will
            // fail this test and force re-validation.
            String src = readSource();
            int sigIdx = src.indexOf("public boolean tryAdvance(java.util.function.Consumer<? super StreamEvent> action) {");
            assertTrue(sigIdx > 0, "tryAdvance signature not found");
            // 60000-char window: the method is ~4000 lines long, so
            // we have to scan a substantial slice to find the catch.
            String window = src.substring(sigIdx, Math.min(sigIdx + 60_000, src.length()));
            assertTrue(window.contains("catch (Throwable t)"),
                    "R735a: tryAdvance must wrap its body in try/catch (Throwable t) so a chat-stream " +
                    "exception surfaces as a RunEnd rather than killing the Spliterator silently. " +
                    "Pre-R735a the user saw `● Idle` with no error banner or retry button.");
            assertTrue(window.contains("runtime_exception"),
                    "R735a: the catch must translate the throw into a RunEnd with stopReason=" +
                    "\"runtime_exception: ...\" so the desktop's failedStopKinds set can route it " +
                    "to the `● Failed (reason)` pill that R735 introduced.");
        }
}
