/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.core.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R746 (2026-10-21) — behavioural tests for the SHARED
 * {@link StreamConsumerGuard}, the helper extracted from R745's
 * dispatcher-local probe so five consumer sites stop hand-rolling the
 * same deadline dance.
 *
 * <h2>What is pinned</h2>
 *
 * <ol>
 *   <li><b>Happy path</b> — a stream that delivers a {@code RunEnd} is
 *       reported as {@link StreamConsumerGuard.Outcome#COMPLETED_WITH_RUN_END},
 *       <em>without</em> waiting out the budget and <em>without</em>
 *       minting a synthetic event.</li>
 *   <li><b>Clean exhaustion</b> — a stream that simply ends (zero
 *       remaining elements) and never sent a {@code RunEnd} gets a
 *       synthetic terminal event, and is explicitly NOT reported as a
 *       stall.</li>
 *   <li><b>Stall</b> — a {@code hasNext()} that parks forever yields
 *       {@link StreamConsumerGuard.Outcome#STALLED} after the budget,
 *       with a {@code consumer_timeout} stopReason.</li>
 *   <li><b>Clean exhaustion ≠ stall</b> — the distinction above is the
 *       subtle one. Conflating the two would inject a bogus
 *       {@code consumer_timeout} into every healthy short stream, which
 *       is a false-positive failure mode that is much worse than the
 *       wedge it guards against.</li>
 *   <li><b>The default is R744's 180s</b> — the guard must not invent a
 *       new number, or an operator who tuned R744 silently fails to tune
 *       this.</li>
 * </ol>
 *
 * <p>Budgets are 2s (not the production 180s) so the stall case finishes
 * in a few hundred milliseconds of wall clock; surefire forks one JVM
 * per module so the real env var cannot be set per-test, which is why
 * {@code consume} takes the budget as a parameter.
 */
class StreamConsumerGuardR746Test {

    /** Short enough that the suite stays fast, long enough that the
     *  probe thread has clearly parked before we assert. */
    private static final long TEST_BUDGET_MS = 2_000L;

    // ------------------------------------------------------------------
    //  1. Happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R746: a stream ending in RunEnd completes without a synthetic event and without paying the timeout")
    void runEndIsTerminalAndImmediate() {
        List<StreamEvent> events = List.of(
                new StreamEvent.TextDelta("hello "),
                new StreamEvent.TextDelta("world"),
                new StreamEvent.RunEnd("end_turn", List.of()));
        AtomicReference<StreamConsumerGuard.Result> ref = new AtomicReference<>();
        AtomicInteger seen = new AtomicInteger();

        long started = System.nanoTime();
        StreamConsumerGuard.Result r = StreamConsumerGuard.consume(
                "test-happy", events.iterator(),
                ev -> seen.incrementAndGet(),
                "case=happy", TEST_BUDGET_MS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(StreamConsumerGuard.Outcome.COMPLETED_WITH_RUN_END, r.outcome(),
                "R746: a delivered RunEnd IS the terminal event — the guard must return success "
                        + "immediately, not wait for the budget and not mint a second terminal event");
        assertTrue(r.sawRunEnd(), "R746: sawRunEnd must be true when the stream delivered RunEnd");
        assertNull(r.syntheticRunEnd(),
                "R746: a healthy stream must NOT get a synthetic terminal event — that would "
                        + "double-fire RunEnd downstream (double span teardown, double turn metric)");
        assertFalse(r.needsSyntheticRunEnd(), "R746: no synthetic event needed on the happy path");
        assertEquals(3, r.eventCount(), "R746: every event, including RunEnd, must be counted");
        assertEquals(3, seen.get(), "R746: every event, including RunEnd, must reach the handler");
        assertTrue(elapsedMs < TEST_BUDGET_MS / 2,
                "R746: the happy path must NOT wait out its budget (took " + elapsedMs
                        + "ms of a " + TEST_BUDGET_MS + "ms budget) — otherwise every run pays the "
                        + "timeout for nothing");
    }

    // ------------------------------------------------------------------
    //  2 + 4. Clean exhaustion (and that it is NOT a stall)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R746: clean exhaustion without RunEnd mints a synthetic terminal event")
    void cleanExhaustionMintsRunEnd() {
        List<StreamEvent> events = List.of(
                new StreamEvent.TextDelta("partial output"),
                new StreamEvent.TextDelta(" then nothing"));
        AtomicReference<String> stopReason = new AtomicReference<>();

        long started = System.nanoTime();
        StreamConsumerGuard.Result r = StreamConsumerGuard.consume(
                "test-exhaust", events.iterator(),
                ev -> { },
                "case=exhaust", TEST_BUDGET_MS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals(StreamConsumerGuard.Outcome.EXHAUSTED_WITHOUT_RUN_END, r.outcome(),
                "R746: a stream that ran out of elements without a RunEnd is the "
                        + "EXHAUSTED_WITHOUT_RUN_END shape — R735's terminal-event contract "
                        + "says the consumer owes its waiter a RunEnd");
        assertNotNull(r.syntheticRunEnd(),
                "R746: the guard MUST mint a RunEnd when the stream drained without one, "
                        + "otherwise the desktop's isStreaming stays on and a dead run reads "
                        + "as a healthy green Idle (the R735 symptom)");
        stopReason.set(r.stopReason());
        assertTrue(stopReason.get().contains("no_run_end"),
                "R746: the synthetic event must be identifiable as no_run_end (got: "
                        + stopReason.get() + ") so postmortems can tell it from a real stop");
        assertFalse(r.sawRunEnd(),
                "R746: sawRunEnd must be false — the ENGINE never sent one, only the guard did");
        assertTrue(r.needsSyntheticRunEnd(),
                "R746: needsSyntheticRunEnd must be true so the caller knows to deliver it");
        assertTrue(elapsedMs < TEST_BUDGET_MS / 2,
                "R746: clean exhaustion must be detected IMMEDIATELY, not after the budget "
                        + "(took " + elapsedMs + "ms) — a healthy stream that simply ends is "
                        + "the common case and must not pay a timeout penalty on the way out");
    }

    @Test
    @DisplayName("R746: clean exhaustion is NOT a stall — the zero-element case must not be misread as consumer_timeout")
    void cleanExhaustionIsNotAStall() {
        // The single most dangerous confusion in this helper. An
        // iterator with ZERO elements hasNext() == false forever. If
        // that is treated as "wedged", every empty-but-healthy stream
        // gets a fabricated consumer_timeout.
        java.util.Iterator<StreamEvent> empty = new ArrayList<StreamEvent>().iterator();
        StreamConsumerGuard.Result r = StreamConsumerGuard.consume(
                "test-empty", empty, ev -> { }, "case=empty", TEST_BUDGET_MS);

        assertFalse(r.stalled(),
                "R746: a zero-element iterator answered hasNext()==false INSIDE the budget — "
                        + "that is a stream that finished, not a stream that wedged. Reporting "
                        + "STALLED here injects a false consumer_timeout into every healthy "
                        + "empty stream");
        assertEquals(StreamConsumerGuard.Outcome.EXHAUSTED_WITHOUT_RUN_END, r.outcome(),
                "R746: the zero-element case belongs to the EXHAUSTED shape");
        assertFalse(r.stopReason().contains("consumer_timeout"),
                "R746: stopReason must not mention a timeout for a stream that drained cleanly "
                        + "(got: " + r.stopReason() + ")");
    }

    // ------------------------------------------------------------------
    //  3. Stall
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R746: a hasNext() that parks forever returns STALLED after the budget")
    void wedgedIteratorStalls() {
        CountDownLatch parked = new CountDownLatch(1);
        // The exact shape that wedges a consumer under an unbounded
        // `while (it.hasNext())`: the producer stops offering elements
        // and the blocking read never returns.
        java.util.Iterator<StreamEvent> wedged = new java.util.Iterator<>() {
            @Override public boolean hasNext() {
                parked.countDown();
                try { new CountDownLatch(1).await(); } catch (InterruptedException ignored) { }
                return false;
            }
            @Override public StreamEvent next() { throw new NoSuchElementException(); }
        };

        long started = System.nanoTime();
        StreamConsumerGuard.Result r = StreamConsumerGuard.consume(
                "test-stall", wedged, ev -> { }, "case=stall", TEST_BUDGET_MS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(r.stalled(),
                "R746: the probe was still parked at the deadline, so this is a STALL. "
                        + "Without this the consumer thread parks forever and whoever was "
                        + "waiting on it (a parent agent, an SDD phase) waits forever too");
        assertEquals(StreamConsumerGuard.Outcome.STALLED, r.outcome(),
                "R746: outcome must be STALLED");
        assertTrue(r.stopReason().startsWith("consumer_timeout"),
                "R746: a stall must be identifiable as consumer_timeout (got: " + r.stopReason()
                        + ") — the downstream stopReason taxonomy keys off this prefix");
        assertTrue(r.stopReason().contains(String.valueOf(TEST_BUDGET_MS)),
                "R746: the stopReason must name the budget that expired (got: " + r.stopReason()
                        + ") so an operator can tell a tuned timeout from a default one");
        assertNotNull(r.syntheticRunEnd(),
                "R746: a stall must still leave a terminal event behind — the caller is not "
                        + "going to wait any longer and R735's contract applies to abandoned "
                        + "streams too");
        assertTrue(r.stopReason().equals(r.syntheticRunEnd().stopReason()),
                "R746: the minted terminal event must carry the same stopReason the caller sees");
        assertTrue(elapsedMs >= TEST_BUDGET_MS / 2,
                "R746: the guard must actually WAIT out its budget before declaring a stall "
                        + "(returned after " + elapsedMs + "ms of " + TEST_BUDGET_MS
                        + "ms) — returning early would kill healthy slow streams");
        assertEquals(0, parked.getCount(),
                "R746: the guard must have actually probed hasNext() rather than "
                        + "short-circuiting");
    }

    @Test
    @DisplayName("R746: a stall after real progress is still a stall, and the event count is preserved")
    void stallAfterProgressPreservesCount() {
        // The realistic unattended-child shape: the subagent streams
        // some text, then the provider wedges. The parent must learn
        // both that work happened and that it never finished.
        CountDownLatch released = new CountDownLatch(1);
        java.util.Iterator<StreamEvent> progressThenWedge = new java.util.Iterator<>() {
            private int step = 0;
            @Override public boolean hasNext() {
                if (step < 3) return true;
                try { released.await(); } catch (InterruptedException ignored) { }
                return false;
            }
            @Override public StreamEvent next() {
                if (step == 0) { step++; return new StreamEvent.TextDelta("partial "); }
                if (step == 1) { step++; return new StreamEvent.ToolUseStart("t1", "bash", java.util.Map.of()); }
                step++;
                return new StreamEvent.TextDelta("more");
            }
        };

        long started = System.nanoTime();
        StreamConsumerGuard.Result r = StreamConsumerGuard.consume(
                "test-progress-stall", progressThenWedge, ev -> { }, "case=progress-stall",
                TEST_BUDGET_MS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(r.stalled(), "R746: the iterator parked after 3 events — still a stall");
        assertEquals(3, r.eventCount(),
                "R746: the events delivered before the wedge must still be counted — the error "
                        + "message the parent reads needs to say how far the child got");
        assertNotNull(r.syntheticRunEnd(),
                "R746: still owes the caller a terminal event");
        assertTrue(r.stopReason().contains("3"),
                "R746: the stopReason / log context should carry the event count for triage");
        assertTrue(elapsedMs >= TEST_BUDGET_MS / 2,
                "R746: the stall must be detected after the budget, not instantly");
    }

    /**
     * R746 regression: the guard must NEVER re-enter the blocking read
     * on the calling thread.
     *
     * <p>An earlier version of {@code probeHasNext} re-invoked
     * {@code it.hasNext()} on the caller after the probe settled, so a
     * provider exception would propagate with its original type. On an
     * iterator with no cached answer — i.e. exactly the wedged producer
     * this helper exists to catch — that second call blocks forever, so
     * the guard detected the stall correctly and then hung anyway.
     *
     * <p>This test drives {@code consume} from a worker thread and
     * joins with a deadline, so a regression shows up as a TIMED-OUT
     * join rather than as a wedged test JVM.
     */
    @Test
    @DisplayName("R746: the calling thread never blocks inside hasNext() — the bound holds end to end")
    void callerThreadNeverBlocksInHasNext() throws Exception {
        CountDownLatch parked = new CountDownLatch(1);
        AtomicInteger callsOnCallerThread = new AtomicInteger();
        Thread caller = Thread.currentThread();

        java.util.Iterator<StreamEvent> wedged = new java.util.Iterator<>() {
            @Override public boolean hasNext() {
                // Count a hasNext() that runs on the TEST's main thread:
                // that is the re-entry this test exists to forbid.
                if (Thread.currentThread() == caller) callsOnCallerThread.incrementAndGet();
                parked.countDown();
                try { new CountDownLatch(1).await(); } catch (InterruptedException ignored) { }
                return false;
            }
            @Override public StreamEvent next() { throw new NoSuchElementException(); }
        };

        java.util.concurrent.atomic.AtomicReference<StreamConsumerGuard.Result> ref =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> ref.set(StreamConsumerGuard.consume(
                "test-no-reentry", wedged, ev -> { }, "case=no-reentry", TEST_BUDGET_MS)));
        worker.setDaemon(true);
        worker.start();

        worker.join(TEST_BUDGET_MS * 4);
        assertFalse(worker.isAlive(),
                "R746: consume() must RETURN on a wedged iterator — the bound is not a bound at "
                        + "all if the calling thread re-enters the blocking hasNext() read. "
                        + "The worker is still parked after " + (TEST_BUDGET_MS * 4) + "ms.");
        assertNotNull(ref.get());
        assertTrue(ref.get().stalled(), "R746: and it must be reported as STALLED");
        assertEquals(0, callsOnCallerThread.get(),
                "R746: hasNext() must be called ONLY on the probe thread. Calling it on the "
                        + "consumer thread is what reintroduces the unbounded park — it happened "
                        + callsOnCallerThread.get() + " time(s) here");
    }

    /** A provider exception must still reach the caller's catch with its
     *  ORIGINAL type (R735a turns it into an error RunEnd), even though
     *  the throw now happens on a probe thread. */
    @Test
    @DisplayName("R746: a provider exception propagates with its original type instead of being swallowed")
    void providerExceptionPropagates() {
        IllegalStateException boom = new IllegalStateException("provider exploded");
        java.util.Iterator<StreamEvent> exploding = new java.util.Iterator<>() {
            @Override public boolean hasNext() { throw boom; }
            @Override public StreamEvent next() { throw new NoSuchElementException(); }
        };

        RuntimeException caught = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> StreamConsumerGuard.consume("test-throw", exploding, ev -> { },
                        "case=throw", TEST_BUDGET_MS),
                "R746: a provider exception must NOT be swallowed — it must reach the caller so "
                        + "R735a's catch can turn it into an error RunEnd instead of leaving the "
                        + "run to die with no terminal event");
        assertTrue(boom == caught || boom == caught.getCause(),
                "R746: the ORIGINAL exception must reach the caller (got: " + caught + "), not a "
                        + "generic wrapper — a swallowed provider fault is what R735a fixed");
    }

    // ------------------------------------------------------------------
    //  5. The shared knob
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R746: the default bounded wait is R744's 180s, and the env var name is shared")
    void defaultMatchesR744Budget() {
        // No env override is present in the surefire JVM (it forks once
        // per module), so this resolves to the default. It must equal
        // R744's FIRST_CHUNK_TIMEOUT_MS default and NOT invent a new
        // number: R744 measured 40-90s cold-start TTFB as healthy, so a
        // shorter default would abandon runs R744 calls healthy.
        assertEquals(180_000L, StreamConsumerGuard.TIMEOUT_MS_DEFAULT,
                "R746: the consumer budget must default to R744's FIRST_CHUNK_TIMEOUT_MS "
                        + "default (180s)");
        assertEquals(180_000L, StreamConsumerGuard.timeoutMs(),
                "R746: timeoutMs() with no env override must return R744's 180s default");
        assertEquals("AETHERCODE_R744_FIRST_CHUNK_TIMEOUT_MS", StreamConsumerGuard.TIMEOUT_ENV,
                "R746: the guard must read R744's existing knob, not mint a new one — an operator "
                        + "who tuned AETHERCODE_R744_FIRST_CHUNK_TIMEOUT_MS must not have to tune "
                        + "a second variable to make the consumer guard behave");
    }

    // ------------------------------------------------------------------
    //  6. awaitNextEvent: the R745-compatible two-valued view
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R746: awaitNextEvent keeps R745's 'may I call next()' shape — drain and stall both answer false")
    void awaitNextEventShapeIsBackwardCompatible() {
        // EngineContinuationDispatcher.awaitNextEvent(it) delegates here.
        // That call site only needs the two-valued answer, so clean
        // drain and stall must both be false — otherwise the
        // dispatcher's own post-loop no_run_end guard would never fire.
        assertFalse(StreamConsumerGuard.awaitNextEvent(
                        new ArrayList<StreamEvent>().iterator(), TEST_BUDGET_MS),
                "R746: a drained iterator must answer false here (no element to call next() for)");
        assertTrue(StreamConsumerGuard.awaitNextEvent(
                        List.<StreamEvent>of(new StreamEvent.TextDelta("x")).iterator(), TEST_BUDGET_MS),
                "R746: an iterator with a pending element must answer true — this is the "
                        + "'proceed with next()' signal the dispatcher loop gates on");
    }
}