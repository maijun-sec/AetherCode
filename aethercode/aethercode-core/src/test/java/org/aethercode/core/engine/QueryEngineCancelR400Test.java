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

import org.aethercode.core.app.AppState;
import org.aethercode.core.llm.ChatClient;
import org.aethercode.core.message.Message;
import org.aethercode.core.metrics.MetricsCollector;
import org.aethercode.core.stream.StreamEvent;
import org.aethercode.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R400 — a run that the user STOPPED must say so.
 *
 * <h2>★ THE GAP R721's TEST DID NOT COVER ★</h2>
 *
 * <p>{@code QueryEngineCancelR721Test} fakes a chat client that blocks inside
 * {@code stream()} and asserts the engine bails with a {@code cancelled}
 * RunEnd. That is the interrupt-arrives-<b>during</b>-the-call shape, and
 * the engine's top-of-loop {@code Thread.interrupted()} check catches it.
 *
 * <p>But the daemon's cancel RPC interrupts a run that is already
 * <b>streaming</b> — the engine has the stream, and it is parked inside
 * {@code hasNext()} waiting for the next chunk. A reactive pipeline treats
 * an interrupt as "cancel this subscription" and <b>completes</b> the flux
 * instead of throwing, so {@code hasNext()} returns {@code false}.
 *
 * <p>Pre-R400 the engine read that truncated stream as "the model finished"
 * and ended the run with a <b>clean end-of-turn reason</b>. Two things then
 * got the wrong answer:
 *
 * <ul>
 *   <li>the renderer showed "✓Done" instead of "⏹ 已停止";</li>
 *   <li>{@code StreamEvent.isDeterministicHardStopOf} does not contain a
 *       clean end-of-turn reason, so the boulder hook's hard-stop gate did
 *       not fire and the 2-second auto-continue countdown could still
 *       resurrect the run the user had just killed.</li>
 * </ul>
 *
 * <p>Measured live (R400): a cancel issued 4.7 s into a streaming run
 * stopped the output — zero further deltas — while the terminal frame
 * carried a clean end-of-turn reason.
 *
 * <h2>★ WHAT THIS GATE HOLDS ★</h2>
 *
 * <ul>
 *   <li>An interrupt that lands while the engine is parked in
 *       {@code hasNext()} ends the run with the bare {@code cancelled}
 *       reason.</li>
 *   <li>A run that was NOT interrupted still ends with the clean
 *       end-of-turn reason — the new branch must not swallow ordinary
 *       completions.</li>
 * </ul>
 */
class QueryEngineCancelR400Test {

    /**
     * A chat client whose stream is EXHAUSTED BY AN INTERRUPT, the way a
     * reactive pipeline behaves: the iterator parks, the interrupt lands,
     * and {@code hasNext()} then reports "no more events" instead of
     * throwing.
     *
     * <p>The interrupt flag is re-set on the worker thread (the same thing
     * {@code QueryEngineCancelR721Test}'s client does) so the engine can
     * still SEE that it was interrupted after {@code hasNext()} returned.
     */
    private static final class InterruptibleStreamChatClient implements ChatClient {
        static volatile Thread workerThread;
        /** set when the iterator parks — the test waits for this before
         *  interrupting, so the interrupt lands in the right place. */
        static volatile boolean parked;

        @Override
        public Stream<StreamEvent> stream(List<Message> m, String sp, List<Tool> tools) {
            workerThread = Thread.currentThread();
            Iterator<StreamEvent> it = new Iterator<>() {
                private int served = 0;

                @Override
                public boolean hasNext() {
                    if (served == 0) return true;
                    // Park until the interrupt lands, then report the
                    // stream as exhausted — a reactive flux COMPLETES on
                    // an interrupt, it does not throw.
                    parked = true;
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    return false;
                }

                @Override
                public StreamEvent next() {
                    served++;
                    return new StreamEvent.TextDelta("partial output");
                }
            };
            return StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED), false);
        }

        @Override public String modelId() { return "stub"; }
    }

    /** A chat client that simply runs out of events — the ORDINARY path. */
    private static final class ExhaustedChatClient implements ChatClient {
        @Override
        public Stream<StreamEvent> stream(List<Message> m, String sp, List<Tool> tools) {
            return Stream.empty();
        }
        @Override public String modelId() { return "stub"; }
    }

    private QueryEngine buildEngine(AppState appState, ChatClient client) {
        org.aethercode.core.engine.PermissionPolicy allowAll =
                (tool, input, ctx) -> java.util.concurrent.CompletableFuture.completedFuture(
                        new org.aethercode.core.permission.PermissionResult.Allow(java.util.Map.of()));
        return new QueryEngine(
                appState, client,
                allowAll, "system",
                msg -> {},
                new MetricsCollector(),
                null, null, null, null);
    }

    private static void awaitTrue(java.util.function.BooleanSupplier cond, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !cond.getAsBoolean()) {
            Thread.sleep(10);
        }
    }

    /**
     * ★ THE REGRESSION PIN ★
     *
     * <p>The interrupt lands while the engine is parked inside
     * {@code hasNext()} — the shape the daemon's cancel RPC actually
     * produces for a run that is already streaming.
     */
    @Test
    void interruptWhileParkedInHasNextEndsTheRunAsCancelled() throws Exception {
        InterruptibleStreamChatClient.workerThread = null;
        InterruptibleStreamChatClient.parked = false;

        AppState st = new AppState("r400-cancel-" + System.nanoTime(), Path.of("."));
        QueryEngine engine = buildEngine(st, new InterruptibleStreamChatClient());

        AtomicBoolean sawRunEnd = new AtomicBoolean(false);
        AtomicReference<String> reason = new AtomicReference<>();
        Thread consumer = new Thread(() -> {
            engine.query("hello").forEach(ev -> {
                if (ev instanceof StreamEvent.RunEnd re) {
                    sawRunEnd.set(true);
                    reason.set(re.stopReason());
                }
            });
        }, "r400-consumer");
        consumer.setDaemon(true);
        consumer.start();

        // wait until the engine has the stream AND is parked inside
        // hasNext() — that is the exact moment the cancel hits.
        awaitTrue(() -> InterruptibleStreamChatClient.workerThread != null
                && InterruptibleStreamChatClient.parked, 3_000);
        assertNotNull(InterruptibleStreamChatClient.workerThread,
                "ChatClient.stream() never started");
        assertTrue(InterruptibleStreamChatClient.parked,
                "the engine never parked inside hasNext() — the test did not "
                        + "reproduce the shape it is meant to pin");

        InterruptibleStreamChatClient.workerThread.interrupt();
        consumer.join(4_000);

        assertTrue(sawRunEnd.get(),
                "no RunEnd emitted after the interrupt — the engine must always "
                        + "close a run with a terminal frame");
        assertEquals("cancelled", reason.get(),
                "a run the user STOPPED must end with the bare 'cancelled' reason. "
                        + "Pre-R400 it ended with a clean end-of-turn reason, which "
                        + "made the renderer show '✓Done' AND left the boulder "
                        + "hook's hard-stop gate un-fired — so the 2s auto-continue "
                        + "could resurrect the run the user had just killed.");
    }

    /**
     * ★ THE CONVERSE ★
     *
     * <p>A run that was NOT interrupted still ends with the clean
     * end-of-turn reason. The new branch keys on the interrupt flag, and a
     * thread that was never interrupted has the flag clear — so the
     * ordinary completion path must be untouched.
     */
    @Test
    void anUninterruptedRunStillEndsCleanly() throws Exception {
        AppState st = new AppState("r400-clean-" + System.nanoTime(), Path.of("."));
        QueryEngine engine = buildEngine(st, new ExhaustedChatClient());

        AtomicBoolean sawRunEnd = new AtomicBoolean(false);
        AtomicReference<String> reason = new AtomicReference<>();
        engine.query("hello").forEach(ev -> {
            if (ev instanceof StreamEvent.RunEnd re) {
                sawRunEnd.set(true);
                reason.set(re.stopReason());
            }
        });

        assertTrue(sawRunEnd.get(), "the ordinary path must still emit a RunEnd");
        assertEquals("end_turn", reason.get(),
                "a run that was never interrupted must end with the clean "
                        + "end-of-turn reason — the cancelled branch must not "
                        + "swallow ordinary completions");
    }
}
