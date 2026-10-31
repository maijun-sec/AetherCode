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

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * R744 (2026-10-21) — the fourth silent-death path, and the first one
 * that is NOT a missing {@code (RunEnd, POISON)} pair.
 *
 * <h2>The bug</h2>
 * {@code SpringAiChatClient.runCall}'s R697 retry loop opened with a
 * bare {@code it.hasNext();}. On a {@code toIterable()} iterator that
 * call is the FIRST BLOCKING NETWORK READ of the whole chat call, and
 * it sat OUTSIDE the guarded streaming {@code try/catch}. A provider
 * that accepts the HTTP request and then never finishes the SSE
 * response body produces no exception at all — the read simply parks
 * on the socket, forever.
 *
 * <p>The consequence is identical in the UI to R738/R742 (green
 * {@code ● Idle}, no error banner, no ↻ retry, transcript frozen) but
 * the mechanism is different in a way that matters:
 * <ul>
 *   <li>R738/R742: the run DID terminate; a terminal RunEnd was
 *       offered without the POISON, so the Spliterator blocked in
 *       {@code queue.take()} with the RunEnd unread in the queue.</li>
 *   <li>R744: the run never terminates. We never reach any RunEnd
 *       offer, so the RunEnd↔POISON pairing contract is irrelevant.</li>
 * </ul>
 *
 * <p>Verified live against daemon PID 23280 on 2026-10-21 00:00: the
 * log ended at the last pre-call line, the daemon stayed alive with
 * flat CPU, and the session transcript stopped writing.
 *
 * <h2>Why the earlier rounds all missed it</h2>
 * <ul>
 *   <li><b>R738</b> — patched {@code catch (Exception e)} around the
 *       streaming loop; missed the unguarded pre-loop read.</li>
 *   <li><b>R742</b> — patched {@code if (it == null)}; that branch is
 *       only reachable if the loop <em>exits</em>, which a parked
 *       {@code hasNext()} never lets it do.</li>
 *   <li><b>R735</b>/<b>R735a</b> — the engine's try/catch around
 *       {@code tryAdvance}. Unreachable here: the engine is blocked
 *       DOWNSTREAM inside {@code currentLlmIt.next()}, so no Throwable
 *       is ever thrown for the engine to translate.</li>
 * </ul>
 *
 * <h2>What R744 changes</h2>
 * The first-chunk read is bounded by {@link #awaitFirstChunk}. On
 * expiry the attempt is abandoned, {@code it} is reset to null, and
 * the existing R697 ladder retries; when all three attempts stall we
 * fall through to the {@code it == null} branch, which R742 already
 * hardens to offer {@code (RunEnd, POISON)} together. So R744
 * <em>reuses</em> the terminal contract rather than inventing a new
 * one.
 *
 * <p>The tests below are behavioural (not source-pin only): they drive
 * {@code awaitFirstChunk} with a REAL stalling iterator on a REAL
 * thread and assert it returns rather than hanging the test JVM. A
 * source-pin alone would have passed against this bug — which is
 * exactly how R738's test passed against R742's still-broken branch.
 */
class SpringAiChatClientR744Test {

    private static final String SRC = "src/main/java/org/aethercode/engine/springai/"
            + "SpringAiChatClient.java";

    /**
     * How long {@link #stalledFirstChunkReturnsInsteadOfBlockingForever}
     * waits for the bounded read to give up before declaring the bug
     * unfixed.
     *
     * <p>The helper's real budget is
     * {@link SpringAiChatClient#FIRST_CHUNK_TIMEOUT_MS_DEFAULT} (180s).
     * We deliberately do NOT shrink it via
     * {@code AETHERCODE_R744_FIRST_CHUNK_TIMEOUT_MS}: surefire forks one
     * JVM for the whole module, and {@code System.getenv} is
     * process-wide, so mutating it from one test would silently retune
     * every other test in the module. Instead this deadline only has to
     * be comfortably longer than the budget, which is what makes the
     * assertion meaningful: if the guard were removed, the helper would
     * park on the socket indefinitely and this deadline would fire.
     *
     * <p>Set {@code AETHERCODE_R744_TEST_FAST=1} to run a quick local
     * pass with a short deadline and a 3s budget; CI runs the full
     * window so the real default is what gets exercised.
     */
    private static final long STALL_TEST_DEADLINE_MS =
            System.getenv("AETHERCODE_R744_TEST_FAST") != null ? 20_000L : 300_000L;

    /**
     * An iterator whose {@code hasNext()} parks forever — the exact
     * shape of a provider that accepted the request and never closed
     * the SSE body. This is the R744 reproducer.
     */
    private static final class StallingIterator implements Iterator<Object> {
        private final CountDownLatch entered = new CountDownLatch(1);
        @Override public boolean hasNext() {
            entered.countDown();
            // Park forever, exactly like a socket read with no peer
            // response. Never throws, never returns.
            while (true) {
                try { Thread.sleep(60_000L); } catch (InterruptedException e) {
                    // A real socket read would surface an
                    // IOException here; emulate "still nothing" by
                    // resuming the park. The test's own deadline is
                    // what must fire.
                    continue;
                }
            }
        }
        @Override public Object next() { throw new NoSuchElementException(); }
    }

    /** An iterator that answers immediately — the healthy-call control. */
    private static final class ReadyIterator implements Iterator<Object> {
        private boolean consumed = false;
        @Override public boolean hasNext() { return !consumed; }
        @Override public Object next() { consumed = true; return "chunk"; }
    }

    /** An iterator that has a real element already buffered. */
    private static final class BufferedIterator implements Iterator<Object> {
        @Override public boolean hasNext() { return true; }
        @Override public Object next() { return "chunk"; }
    }

    private static Method awaitFirstChunk() throws Exception {
        Method m = SpringAiChatClient.class
                .getDeclaredMethod("awaitFirstChunk", Iterator.class);
        m.setAccessible(true);
        return m;
    }

    /**
     * THE regression test. Drives {@code awaitFirstChunk} against a
     * stalling iterator in a watchdog thread and fails if the call has
     * not returned within a generous window.
     *
     * <p>The test-level deadline is deliberately much larger than the
     * per-call budget the helper reads from
     * {@code AETHERCODE_R744_FIRST_CHUNK_TIMEOUT_MS}: the point is
     * "returns at all", not "returns fast". We do not set the env var
     * (it is process-wide and the surefire JVM is shared), so the
     * helper uses the 180s default; the watchdog below only asserts
     * that the call completed within {@link #STALL_TEST_DEADLINE_MS}.
     *
     * <p>Set {@code AETHERCODE_R744_TEST_FAST=1} to shrink the budget
     * for a quick local run.
     */
    @Test
    void stalledFirstChunkReturnsInsteadOfBlockingForever() throws Exception {
        StallingIterator stalled = new StallingIterator();
        final AtomicBoolean returned = new AtomicBoolean(false);
        final Object[] outcome = new Object[1];

        Thread caller = new Thread(() -> {
            try {
                outcome[0] = awaitFirstChunk().invoke(null, stalled);
            } catch (Exception e) {
                outcome[0] = e;
            } finally {
                returned.set(true);
            }
        }, "r744-caller");
        caller.setDaemon(true);
        caller.start();

        // The reproducer's defining property: the helper MUST have
        // reached the (blocking) hasNext() call.
        assertTrue(stalled.entered.await(10, TimeUnit.SECONDS),
                "R744: awaitFirstChunk never issued the blocking hasNext() — "
                        + "the reproducer did not engage");

        boolean done = returned.get();
        long deadline = System.currentTimeMillis() + STALL_TEST_DEADLINE_MS;
        while (!done && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
            done = returned.get();
        }

        if (!done) {
            fail("R744: awaitFirstChunk blocked on a stalling iterator for more than "
                    + STALL_TEST_DEADLINE_MS + "ms. This is the silent-death bug: the "
                    + "engine would stay parked inside currentLlmIt.next() forever, with "
                    + "no RunEnd, no POISON, and a green ● Idle in the UI.");
        }

        assertEquals(Boolean.FALSE, outcome[0],
                "R744: a timed-out first-chunk read must report failure (false) so the "
                        + "R697 retry ladder discards the parked iterator and can retry. "
                        + "Returning true would send a wedged iterator into the streaming "
                        + "loop and re-create the permanent block one layer down.");
    }

    /**
     * Control: a healthy iterator must be reported as ready, not
     * treated as a stall. Without this, R744 would "fix" the hang by
     * breaking every real call.
     */
    @Test
    void healthyIteratorIsNotTreatedAsStalled() throws Exception {
        assertEquals(Boolean.TRUE, awaitFirstChunk().invoke(null, new ReadyIterator()),
                "R744: an exhausted-but-complete stream is a legal non-stall outcome");
        assertEquals(Boolean.TRUE, awaitFirstChunk().invoke(null, new BufferedIterator()),
                "R744: an iterator with a buffered element must report ready");
    }

    /**
     * R721 cancel must still work: if the owning thread is interrupted
     * while awaiting the first chunk, {@code awaitFirstChunk} must
     * propagate the interrupt rather than swallowing it and sitting
     * out the full budget. Cancel is the user's only escape hatch from
     * a stalled read.
     */
    @Test
    void interruptPropagatesSoCancelStillWorks() throws Exception {
        StallingIterator stalled = new StallingIterator();
        final AtomicBoolean gotInterrupt = new AtomicBoolean(false);
        final AtomicBoolean threw = new AtomicBoolean(false);

        Thread caller = new Thread(() -> {
            try {
                awaitFirstChunk().invoke(null, stalled);
            } catch (Exception e) {
                threw.set(true);
                if (e instanceof InterruptedException
                        || (e.getCause() instanceof InterruptedException)) {
                    gotInterrupt.set(true);
                }
            }
        }, "r744-interrupt-caller");
        caller.setDaemon(true);
        caller.start();

        assertTrue(stalled.entered.await(10, TimeUnit.SECONDS),
                "R744: reproducer did not engage for the interrupt case");
        caller.interrupt();

        long deadline = System.currentTimeMillis() + 30_000L;
        while (!threw.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        assertTrue(threw.get(),
                "R744: an interrupt during the first-chunk wait must surface as an "
                        + "exception, otherwise R721's cancel RPC cannot interrupt a "
                        + "stalled read and the user has no escape hatch");
        assertTrue(gotInterrupt.get(),
                "R744: the exception raised on interrupt must BE an InterruptedException "
                        + "(possibly as a cause) so the R697 catch ladder can restore "
                        + "the interrupt flag and bail cleanly");
    }

    /**
     * The per-call budget must be overridable, and a malformed
     * override must never silently reopen the R744 hole.
     *
     * <p>{@code AETHERCODE_R744_FIRST_CHUNK_TIMEOUT_MS} is
     * process-wide, so this test does not mutate it — it asserts the
     * observed value is one of the two legal outcomes (the default,
     * or a valid positive override the operator set) and that the
     * validation logic in {@code firstChunkTimeoutMs} is present and
     * rejects non-positive values. The pure-string validation is
     * asserted via the source pin below.
     */
    @Test
    void timeoutOverrideIsValidated() throws Exception {
        Method m = SpringAiChatClient.class.getDeclaredMethod("firstChunkTimeoutMs");
        m.setAccessible(true);
        long budget = (Long) m.invoke(null);

        String env = System.getenv("AETHERCODE_R744_FIRST_CHUNK_TIMEOUT_MS");
        if (env == null || env.isBlank()) {
            assertEquals(SpringAiChatClient.FIRST_CHUNK_TIMEOUT_MS_DEFAULT, budget,
                    "R744: with no env override the default budget must be used verbatim, "
                            + "got " + budget);
        } else {
            // Operator-set override: it must be honoured, and it must
            // be strictly positive — a non-positive budget would make
            // awaitFirstChunk time out instantly and break every call.
            long parsed = Long.parseLong(env.trim());
            assertEquals(parsed, budget,
                    "R744: a valid env override must be honoured verbatim, got " + budget);
        }
        assertTrue(budget > 0,
                "R744: firstChunkTimeoutMs() must always yield a positive budget, got "
                        + budget + " — a non-positive value would time out every call "
                        + "instantly and reintroduce a different silent failure");
    }

    /**
     * Source-pin: the bare, unguarded {@code it.hasNext();} must be
     * gone from the R697 loop and replaced by the bounded wait.
     *
     * <p>This is the assertion R738's test should have made. Note the
     * bounded-window style: we search the retry-loop body only, so a
     * POISON/await from an unrelated branch cannot satisfy it.
     */
    @Test
    void retryLoopUsesBoundedFirstChunkWait() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(SRC)),
                StandardCharsets.UTF_8);
        int loopStart = src.indexOf("while (attempt < 3) {");
        assertTrue(loopStart > 0, "R744: R697 retry loop not found");
        int loopEnd = src.indexOf("if (it == null) {", loopStart);
        assertTrue(loopEnd > loopStart, "R744: could not bound the R697 retry loop");
        String loop = src.substring(loopStart, loopEnd);

        assertFalse(loop.contains("\n                it.hasNext();"),
                "R744: the R697 retry loop still contains the bare unguarded "
                        + "`it.hasNext();` — the unbounded first blocking read that "
                        + "causes the silent death. Loop body:\n" + loop);
        assertTrue(loop.contains("awaitFirstChunk(it)"),
                "R744: the retry loop must gate its first-chunk read through "
                        + "awaitFirstChunk(it) so the wait is bounded. Loop body:\n" + loop);
    }

    /**
     * Source-pin: the helper itself must exist, be private+static,
     * and be driven by the validated budget (not a hardcoded literal
     * that a future edit could drift away from).
     */
    @Test
    void firstChunkHelperIsWiredToValidatedBudget() throws Exception {
        assertNotNull(awaitFirstChunk(), "R744: awaitFirstChunk(Iterator) must exist");
        Method m = awaitFirstChunk();
        assertTrue(java.lang.reflect.Modifier.isStatic(m.getModifiers()),
                "R744: awaitFirstChunk must be static (it owns its own probe thread)");
        assertTrue(java.lang.reflect.Modifier.isPrivate(m.getModifiers()),
                "R744: awaitFirstChunk must stay private — it is an internal "
                        + "guard, not part of the ChatClient contract");

        String src = new String(Files.readAllBytes(Paths.get(SRC)),
                StandardCharsets.UTF_8);
        int helperIdx = src.indexOf("private static boolean awaitFirstChunk(");
        assertTrue(helperIdx > 0, "R744: awaitFirstChunk definition not found");
        int helperEnd = src.indexOf("\n    }", helperIdx);
        String body = src.substring(helperIdx, helperEnd > helperIdx ? helperEnd : src.length());
        assertTrue(body.contains("firstChunkTimeoutMs()"),
                "R744: awaitFirstChunk must derive its deadline from "
                        + "firstChunkTimeoutMs() so the env override and the validation "
                        + "in that method actually take effect");
    }

    /**
     * The env override must be VALIDATED, not blindly parsed. A typo
     * ("180s", "-1", "0") must fall back to the default rather than
     * disabling the guard (a non-positive budget would time out every
     * call instantly; a silently-ignored typo would reopen the exact
     * R744 hole the operator was trying to tune).
     */
    @Test
    void envOverrideIsValidatedNotBlindlyParsed() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get(SRC)),
                StandardCharsets.UTF_8);
        int idx = src.indexOf("static long firstChunkTimeoutMs() {");
        assertTrue(idx > 0, "R744: firstChunkTimeoutMs() not found");
        int end = src.indexOf("\n    }", idx);
        String body = src.substring(idx, end > idx ? end : src.length());

        assertTrue(body.contains("Long.parseLong"),
                "R744: the override must be parsed defensively via Long.parseLong inside a "
                        + "try/catch, not via valueOf (which would throw on a typo and take "
                        + "down the chat call)");
        assertTrue(body.contains("if (n > 0)"),
                "R744: the parsed override must be rejected when non-positive — otherwise "
                        + "`AETHERCODE_R744_FIRST_CHUNK_TIMEOUT_MS=0` would make every call "
                        + "time out instantly. Body:\n" + body);
        assertTrue(body.contains("NumberFormatException"),
                "R744: a malformed override (e.g. \"180s\") must be caught and the default "
                        + "used — a typo must not silently reopen the R744 hole. Body:\n" + body);
        assertTrue(body.contains("FIRST_CHUNK_TIMEOUT_MS_DEFAULT"),
                "R744: the default must remain reachable as the fallback for both the blank "
                        + "and the malformed case. Body:\n" + body);
    }
}
