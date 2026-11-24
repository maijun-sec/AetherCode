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
            // Lesson 1107: a fixed 1200-char window silently stopped
            // covering the `runtime_exception` (outer catch) branch once
            // R750 grew the explanatory comment above its
            // `action.accept(new StreamEvent.RunEnd(...))`. A real
            // missing RunEnd and a merely-longer comment were then
            // indistinguishable — same failure mode as Lesson 1057.
            // The reliable boundary for the OUTER CATCH is the catch
            // block's own end; for every other branch the tight window
            // is fine because its comment blocks are short.
            String window;
            if ("runtime_exception: ".equals(reason)) {
                int endMarker = src.indexOf("return StreamSupport.stream(sp, false);", after);
                window = src.substring(after, endMarker < 0 ? Math.min(after + 1200, src.length()) : endMarker);
            } else {
                window = src.substring(after, Math.min(after + 1200, src.length()));
            }
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

    /** The R735a stop reason, matched as CODE. The R735 scan above
     *  already assumes this shape (`stopReason = "X";`), and the
     *  R744 note in the source warns that the shape must never be
     *  reproduced inside a comment (Lesson 1057). */
    private static final Pattern RUNTIME_EXCEPTION_ASSIGNMENT =
            Pattern.compile("stopReason\\s*=\\s*\"runtime_exception: ");

    /**
     * R735a (2026-10-20) — the LLM stream can throw mid-iteration
     * (e.g. upstream 500 from the chat provider that escaped the
     * chat-client retry loop, or any WebClientResponseException
     * that the reactive pipeline propagates). Pre-R735a, the throw
     * escaped tryAdvance and killed the Spliterator with no terminal
     * RunEnd emitted — the desktop sat in the green {@code ● Idle}
     * state with no error banner / no retry affordance, and the user
     * thought the engine was still running. The fix wraps the
     * entire tryAdvance body in try/catch and translates the throw
     * into a single RunEnd whose stopReason is prefixed
     * {@code runtime_exception}.
     *
     * <p>R401 (2026-11-24) rewrote this pin. It used to be a
     * CHARACTER WINDOW — the 60 000 chars after {@code tryAdvance}'s
     * signature — which made it measure the LAYOUT of the method
     * instead of its contract. Two defects fell out of that:
     *
     * <p>(1) THE PIN WAS SATISFIED BY PROSE. The window was searched
     * for the bare word {@code runtime_exception}, and the FIRST such
     * occurrence in the file is a COMMENT inside the catch ("stopReason
     * is prefixed {@code runtime_exception} so …") — not the
     * assignment. The real assignment sat ~2.5 kB further out,
     * OUTSIDE the window. So the assertion "the catch translates the
     * throw into a RunEnd with stopReason=runtime_exception" had
     * never actually looked at the assignment. A pin that passes for
     * the wrong reason is worse than no pin: it reads as coverage.
     *
     * <p>(2) IT BROKE ON AN UNRELATED EDIT. tryAdvance grew by ~600
     * chars of R401 code, the comment occurrence crossed the 60 000
     * boundary, and the pin went red while the code it protects was
     * intact and correct. A pin that reports "the catch is missing"
     * when the catch is right there teaches the next reader to
     * distrust the file — and to "fix" it by deleting the pin.
     *
     * <p>The rewrite pins the INVARIANT with structural anchors
     * instead of a distance:
     * <ol>
     *   <li>the {@code stopReason = "runtime_exception: "} ASSIGNMENT
     *       (code, not prose) must exist;</li>
     *   <li>it must live inside {@code tryAdvance}'s body;</li>
     *   <li>the nearest enclosing {@code catch (Throwable t)} block
     *       must contain it (brace matching, with comments and
     *       literals skipped);</li>
     *   <li>that catch must emit a terminal {@code StreamEvent.RunEnd}
     *       for the same stop reason.</li>
     * </ol>
     * No character window, so the method may grow freely.
     */
    @Test
    void tryAdvance_hasOuterTryCatchRuntimeException() {
        String src = readSource();

        int sigIdx = src.indexOf("public boolean tryAdvance(java.util.function.Consumer<? super StreamEvent> action) {");
        assertTrue(sigIdx > 0, "tryAdvance signature not found");
        int bodyOpen = src.indexOf('{', sigIdx);
        assertTrue(bodyOpen > sigIdx, "R735a: tryAdvance's body brace not found");
        int bodyEnd = matchBrace(src, bodyOpen);
        assertTrue(bodyEnd > bodyOpen,
                "R735a: could not brace-match tryAdvance's body. Either the source is malformed or "
                + "the brace scanner in this test is wrong — and a pin that cannot read its subject "
                + "must fail loudly rather than pass vacuously (Lesson 1236).");

        // (1) the ASSIGNMENT — code, not the comment that names it.
        Matcher am = RUNTIME_EXCEPTION_ASSIGNMENT.matcher(src);
        assertTrue(am.find(),
                "R735a: no `stopReason = \"runtime_exception: \"` assignment in QueryEngine.java. The "
                + "outer catch no longer translates a chat-stream throw into a terminal RunEnd, so a "
                + "provider 500 kills the Spliterator with no run_end — the desktop stays in the green "
                + "`● Idle` state with no error banner and no retry affordance.");
        int assignIdx = am.start();
        assertTrue(assignIdx > bodyOpen && assignIdx < bodyEnd,
                "R735a: the runtime_exception assignment is outside tryAdvance's body (at " + assignIdx
                + ", body " + bodyOpen + ".." + bodyEnd + ") — this pin describes the outer catch of "
                + "tryAdvance, not some other method.");

        // (2) the nearest enclosing catch block.
        int catchIdx = src.lastIndexOf("catch (Throwable t)", assignIdx);
        assertTrue(catchIdx > bodyOpen,
                "R735a: no `catch (Throwable t)` precedes the runtime_exception assignment inside "
                + "tryAdvance — the outer try/catch wrapper is gone.");
        int catchOpen = src.indexOf('{', catchIdx);
        int catchEnd = matchBrace(src, catchOpen);
        assertTrue(catchEnd > catchOpen,
                "R735a: could not brace-match the catch block — the source-pin cannot be trusted in "
                + "that state.");
        assertTrue(assignIdx < catchEnd,
                "R735a: the runtime_exception assignment is NOT inside the `catch (Throwable t)` "
                + "block that precedes it, so that catch is not the one translating the throw.");

        // (3) the terminal RunEnd, emitted by that same catch.
        int runEndIdx = src.indexOf("new StreamEvent.RunEnd(", assignIdx);
        assertTrue(runEndIdx > 0 && runEndIdx < catchEnd,
                "R735a: the catch that translates the throw must emit a terminal StreamEvent.RunEnd "
                + "for the runtime_exception stop reason — otherwise the desktop's run_end handler "
                + "cannot flip isStreaming=false, populate lastFailedPrompt, or surface the "
                + "`● Failed (reason)` pill that R735 introduced.");
    }

    /**
     * R401: a scanner with no self-test has its bugs found by whichever
     * gate happens to trip over them (Lesson 1235). The brace matcher
     * is load-bearing for the pin above, so it gets its own test using
     * the shapes that actually appear in Java source: a brace inside a
     * line comment, inside a block comment, inside a string literal,
     * inside a char literal, and inside a text block.
     */
    @Test
    void matchBrace_ignoresBracesInCommentsAndLiterals() {
        String src = "class X {\n"
                + "  void f() { // a comment with } and {\n"
                + "    /* block } comment { */\n"
                + "    String s = \"}\";\n"
                + "    char c = '{';\n"
                + "    String t = \"\"\"\n"
                + "      } in a text block\n"
                + "      \"\"\";\n"
                + "  }\n"
                + "}\n";
        int open = src.indexOf('{', src.indexOf("void f()"));
        int end = matchBrace(src, open);
        assertTrue(end > 0, "matchBrace must find the closing brace of the method body");
        assertTrue(src.substring(open, end).contains("text block"),
                "matchBrace must SKIP the text block's braces, not mistake them for the method's end");
        assertTrue("}".equals(src.substring(end - 1, end)),
                "matchBrace must return the index just PAST the closing brace");
        int classOpen = src.indexOf('{');
        assertTrue(matchBrace(src, classOpen) > end,
                "matchBrace must not confuse the class body with the method body");
        // An unterminated block must fail loudly, never silently "succeed".
        assertTrue(matchBrace("{ unterminated", 0) == -1,
                "matchBrace must return -1 for an unterminated block — a pin that cannot read its "
                + "subject must fail loudly rather than pass vacuously (Lesson 1236).");
    }

    /**
     * Return the index just PAST the {@code }} that closes the block
     * whose {@code &#123;} sits at {@code openIdx}.
     *
     * <p>Comments, string literals, char literals and text blocks are
     * skipped, so a brace inside prose or inside a literal cannot move
     * the depth. Returns {@code -1} when the source is malformed (an
     * unterminated block or literal) — the caller MUST treat that as a
     * FAILURE, never as "no match" (Lesson 1236: fail loudly rather
     * than lie).
     */
    private static int matchBrace(String src, int openIdx) {
        if (openIdx < 0 || openIdx >= src.length() || src.charAt(openIdx) != '{') return -1;
        int depth = 0;
        int i = openIdx;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                int nl = src.indexOf('\n', i);
                if (nl < 0) return -1;
                i = nl + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int close = src.indexOf("*/", i + 2);
                if (close < 0) return -1;
                i = close + 2;
                continue;
            }
            if (c == '"') {
                if (src.startsWith("\"\"\"", i)) {
                    int close = src.indexOf("\"\"\"", i + 3);
                    if (close < 0) return -1;
                    i = close + 3;
                    continue;
                }
                int after = skipLiteral(src, i, '"');
                if (after < 0) return -1;
                i = after;
                continue;
            }
            if (c == '\'') {
                int after = skipLiteral(src, i, '\'');
                if (after < 0) return -1;
                i = after;
                continue;
            }
            if (c == '{') {
                depth++;
                i++;
                continue;
            }
            if (c == '}') {
                depth--;
                i++;
                if (depth == 0) return i;
                continue;
            }
            i++;
        }
        return -1;
    }

    /**
     * Skip a quoted literal whose opening quote sits at {@code start};
     * return the index just past its closing quote, or {@code -1} if it
     * is unterminated.
     */
    private static int skipLiteral(String src, int start, char quote) {
        int i = start + 1;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) return i + 1;
            // A raw newline cannot occur inside a Java string or char
            // literal — so hitting one means the literal never closed.
            if (c == '\n') return -1;
            i++;
        }
        return -1;
    }
}
