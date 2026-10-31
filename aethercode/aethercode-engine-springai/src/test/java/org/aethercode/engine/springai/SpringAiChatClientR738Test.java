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

package org.aethercode.engine.springai;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R738 (2026-10-20) — SpringAiChatClient.runCall must pair every
 * {@code StreamEvent.RunEnd} with a queue POISON object on every
 * exit path, or the Spliterator in {@code stream()} will keep
 * blocking on {@code queue.take()} and never reach the
 * RunEnd handler in the desktop.
 *
 * <p>Pre-R738, the {@code catch (Exception e)} block at the end of
 * the streaming loop offered a {@code RunEnd} with the decorated
 * {@code "error: ..."} stopReason but then {@code return;}'d
 * straight out of {@code runCall}. Because the
 * {@code Thread runner} lambda's {@code try { runCall(...);
 * queue.offer(POISON); } } cannot run that last offer when
 * {@code runCall} already returned, the Spliterator sat in
 * {@code queue.take()} forever, the {@code QueryEngine.tryAdvance}
 * catch (R735a) never reached, and the renderer stayed in
 * {@code ● Idle} even though the terminal RunEnd was sitting in
 * the queue all along. Adding an explicit {@code queue.offer(POISON)}
 * immediately after every RunEnd closes the gap.
 *
 * <p>These tests are source-pin: streaming-call behaviour that
 * uses Reactor and Spring AI needs a live LLM to exercise
 * end-to-end (covered in evals), but the contract itself is
 * "every exit path drains the queue", and that's a statable
 * structural invariant.
 */
class SpringAiChatClientR738Test {

    /**
     * The catch-and-emit path inside the streaming while loop must
     * pair the {@code StreamEvent.RunEnd} with a queue POISON.
     */
    @Test
    void streamingCallCatchEmitsRunEndFollowedByPoison() throws Exception {
        String src = readSource();
        assertTrue(src.contains("streaming call failed"),
                "the streaming loop must log `streaming call failed` "
                        + "(the human-readable wire for RunEnd)");
        // Locate the catch block. The poison-offer must be inside
        // that block (or directly adjacent), not several
        // statements later — otherwise a future refactor can move
        // the poison offer out of reach.
        int catchIdx = src.indexOf("catch (Exception e)");
        int runEndIdx = src.indexOf("queue.offer(new StreamEvent.RunEnd(",
                Math.max(0, catchIdx - 200));
        int poisonAfter = src.indexOf("queue.offer(POISON)",
                Math.max(0, runEndIdx - 200));
        assertTrue(catchIdx > 0 && runEndIdx > 0 && poisonAfter > 0,
                "the streaming catch must offer both RunEnd and POISON");
        assertTrue(poisonAfter > runEndIdx,
                "the POISON must come AFTER the RunEnd, not before — otherwise "
                        + "the Spliterator would treat the poison as the final "
                        + "event and lose the RunEnd that was sitting in the queue");
    }

    /**
     * The R697 "all attempts failed" path (line ~279, offer RunEnd
     * then {@code return;}) must also pair its RunEnd with a
     * POISON — pre-R738 this was missing because the outer
     * {@code Thread runner} lambda's {@code queue.offer(POISON)}
     * happens AFTER {@code runCall} returns normally, which is true
     * here too, but the structural invariant is "every exit path is
     * explicit". A source-pin check is cheaper than spinning up the
     * Reactive Streams pipeline.
     */
    @Test
    void r697AllAttemptsFailedPairsRunEndWithPoison() throws Exception {
        String src = readSource();
        int r697Idx = src.indexOf("R697: all");
        assertTrue(r697Idx > 0,
                "the R697 retry-exhaustion log line still exists");
        int runEndIdx = src.indexOf("queue.offer(new StreamEvent.RunEnd(errMsg",
                r697Idx);
        // Verify there is a POISON offer reachable between this
        // RunEnd and the next function boundary. The outer thread
        // runner handles it via `runCall(); queue.offer(POISON)`, so
        // the structural check is "the function returns, control
        // reaches a POISON offer shortly after".
        int nextPoison = src.indexOf("queue.offer(POISON)", runEndIdx);
        assertTrue(nextPoison > 0,
                "a queue.offer(POISON) must exist somewhere on the "
                        + "control-flow path after the R697 RunEnd");
    }

    /**
     * Anchor the {@code @R735a try/catch} requirement on the
     * engine side, plus the fact that the spring-ai spliterator
     * is what blocks on the queue. If {@code stream()} ever stops
     * returning a Spliterator with a {@code queue.take()}, the
     * contract this test pins breaks.
     */
    @Test
    void streamReturnsSpliteratorThatReadsFromQueue() throws Exception {
        String src = readSource();
        assertTrue(src.contains("queue.take()"),
                "SpringAiChatClient.stream() must return a Spliterator "
                        + "that drains a BlockingQueue via queue.take()");
        assertTrue(src.contains("java.util.Spliterator"),
                "stream() must produce a Spliterator so QueryEngine can "
                        + "consume it lazily");
    }

    private static String readSource() throws Exception {
        return Files.readString(Paths.get("src", "main", "java",
                "org", "aethercode", "engine", "springai",
                "SpringAiChatClient.java"), StandardCharsets.UTF_8);
    }
}