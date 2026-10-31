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
import org.aethercode.core.message.ContentBlock;
import org.aethercode.core.message.Message;
import org.aethercode.core.message.Role;
import org.aethercode.core.metrics.MetricsCollector;
import org.aethercode.core.stream.StreamEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R721: cancel RPC must settle within a short bound (≤ 5 seconds
 * + 100ms slack) instead of waiting for the model to yield a
 * natural RunEnd. Pre-R720 the cancel RPC only flipped the
 * future's cancel flag, which no in-flight code path actually
 * observed — a stuck LLM stream would block the next query for
 * 60-120 seconds. R720 added the runThreads map and the
 * thread.interrupt() call; R720 added the Thread.interrupted()
 * check at the top of QueryEngine.tryAdvance. The two changes
 * together settle the run at the next iteration boundary
 * (microseconds for a normal {@code stream.read} to bail).
 *
 * <p>This test fakes a chat client that blocks the calling
 * thread for 10 seconds inside {@code stream()} and verifies
 * that the engine's tryAdvance bails within milliseconds when
 * the worker thread is interrupted. The point isn't to test
 * the cancel RPC (that's AetherCodeMethods.cancel — covered
 * by integration tests on the daemon); it's to test the
 * QueryEngine layer's responsiveness to the interrupt signal.
 */
class QueryEngineCancelR721Test {

    /** Chat client that blocks the calling thread inside
     *  {@code stream()}. The runId field is captured into
     *  a static so a separate test thread can interrupt
     *  the right worker. Reset to null at the start of
     *  each test (the {@code @BeforeEach} below) so a
     *  stale reference from a previous run doesn't
     *  short-circuit the waiter loop. */
    static volatile Thread workerThread;

    @org.junit.jupiter.api.BeforeEach
    void resetWorkerThread() {
        workerThread = null;
    }

    private static class BlockingChatClient implements ChatClient {
        @Override
        public Stream<StreamEvent> stream(List<Message> m, String sp, List<org.aethercode.core.tool.Tool> tools) {
            workerThread = Thread.currentThread();
            try {
                // block for 10 seconds; cancel should bail
                // long before this returns.
                Thread.sleep(10_000);
            } catch (InterruptedException ie) {
                // the cancel RPC's thread.interrupt() reached
                // us — that's the path R720 fixed.
                Thread.currentThread().interrupt();
            }
            return Stream.empty();
        }
        @Override public String modelId() { return "stub"; }
    }

    private QueryEngine buildEngine(AppState appState) {
        org.aethercode.core.engine.PermissionPolicy allowAll =
                (tool, input, ctx) -> java.util.concurrent.CompletableFuture.completedFuture(
                        new org.aethercode.core.permission.PermissionResult.Allow(java.util.Map.of()));
        return new QueryEngine(
                appState, new BlockingChatClient(),
                allowAll, "system",
                msg -> {},
                new MetricsCollector(),
                null, null, null, null);
    }

    /** measure how long the engine takes to reach a terminal
     *  RunEnd after a separate thread interrupts the worker.
     *  Pre-R720 this took 60-120s (cancel is a no-op until
     *  the model yields). R720's interrupt-check at the top
     *  of tryAdvance bails in microseconds. */
    @Test
    void interruptSettlesRunInMilliseconds() throws Exception {
        AppState st = new AppState(
                "r721-cancel-" + System.nanoTime(),
                java.nio.file.Path.of("."));
        QueryEngine engine = buildEngine(st);
        // stamp a start time and run the query on a background
        // thread. The query will block inside ChatClient.stream
        // for 10s; we'll interrupt the worker and confirm the
        // stream ends within 1 second.
        long startMs = System.currentTimeMillis();
        Thread consumer = new Thread(() -> {
            engine.query("hello").forEach(ev -> {
                // no-op consumer — we just want to drive
                // the Spliterator so the worker thread is
                // born.
            });
        }, "r721-consumer");
        consumer.setDaemon(true);
        consumer.start();
        // wait until the worker thread is registered (i.e.
        // ChatClient.stream() has started blocking). Poll
        // for up to 2 seconds.
        Thread waiter = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                if (workerThread != null) return;
                try { Thread.sleep(10); } catch (InterruptedException ie) { return; }
            }
        });
        waiter.setDaemon(true);
        waiter.start();
        waiter.join(2_500);
        assertNotNull(workerThread, "ChatClient.stream() never started — can't test cancel");
        // interrupt the worker, simulating the cancel RPC's
        // thread.interrupt() call.
        workerThread.interrupt();
        // wait for the consumer to finish. The engine's
        // tryAdvance should bail at the next iteration, the
        // Spliterator returns false, the forEach completes,
        // and the consumer thread exits.
        consumer.join(2_000);
        long elapsedMs = System.currentTimeMillis() - startMs;
        assertTrue(consumer.isAlive() == false,
                "consumer thread still alive after interrupt; "
                        + "QueryEngine.tryAdvance didn't honour the interrupt");
        // Total elapsed should be under 2 seconds (the join
        // bound). The interrupt itself is microseconds; the
        // rest is the wait for the worker thread to register
        // and the consumer to drive one more iteration.
        assertTrue(elapsedMs < 2_000,
                "cancel-settle took " + elapsedMs + "ms; should be < 2s. "
                        + "Pre-R720 this was 60-120s because the cancel "
                        + "RPC was a soft future flag flip with no interrupt path.");
    }

    /** verify the cancel-via-interrupt path emits a final
     *  RunEnd with stop_reason="cancelled" so the renderer's
     *  transcript shows the run ended, not errored. */
    @Test
    void interruptEmitsCancelledRunEnd() throws Exception {
        AppState st = new AppState(
                "r721-cancel-end-" + System.nanoTime(),
                java.nio.file.Path.of("."));
        QueryEngine engine = buildEngine(st);
        AtomicBoolean sawRunEnd = new AtomicBoolean(false);
        AtomicLong observedStopReason = new AtomicLong(0); // we'll read the string via the event
        StringBuilder lastReason = new StringBuilder();
        Thread consumer = new Thread(() -> {
            engine.query("hello").forEach(ev -> {
                if (ev instanceof StreamEvent.RunEnd re) {
                    sawRunEnd.set(true);
                    lastReason.setLength(0);
                    lastReason.append(re.stopReason());
                }
            });
        }, "r721-consumer-2");
        consumer.setDaemon(true);
        consumer.start();
        Thread waiter = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                if (workerThread != null) return;
                try { Thread.sleep(10); } catch (InterruptedException ie) { return; }
            }
        });
        waiter.setDaemon(true);
        waiter.start();
        waiter.join(2_500);
        assertNotNull(workerThread, "ChatClient.stream() never started");
        workerThread.interrupt();
        consumer.join(2_000);
        assertTrue(sawRunEnd.get(),
                "no RunEnd emitted after interrupt; tryAdvance "
                        + "must emit a terminal RunEnd so the renderer's "
                        + "transcript shows the run ended");
        assertEquals("cancelled", lastReason.toString(),
                "RunEnd stop reason must be 'cancelled' so the "
                        + "renderer can distinguish cancel from error");
    }
}
