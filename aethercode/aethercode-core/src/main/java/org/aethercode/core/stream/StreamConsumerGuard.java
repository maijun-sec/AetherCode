/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.core.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.function.Consumer;

/**
 * R746: shared bounded-wait + terminal-event guard for engine /
 * chat-client stream consumers.
 *
 * <p>Extracted from {@code EngineContinuationDispatcher}'s R745 fix so
 * the five consumer sites (continuation dispatcher, subagent
 * orchestrator, {@code AgentTool}, {@code SddOrchestrator}, and any
 * future one) share one implementation instead of five copies of the
 * same probe-thread dance.
 *
 * <h2>Why the guard exists</h2>
 *
 * <p>{@code hasNext()} on a Spliterator-backed stream is itself a
 * blocking read: it parks until the producer offers an element. A
 * plain {@code forEach} / {@code while (it.hasNext())} loop therefore
 * has <strong>no upper bound</strong> — a producer whose backing queue
 * never receives another element parks the consumer thread forever.
 * Nothing throws. The caller (a parent agent waiting on
 * {@code subagent_status}, a phase stuck in {@code running}, the
 * session run lock held open) simply waits forever.
 *
 * <p>Two distinct failure shapes need two distinct answers:
 *
 * <ul>
 *   <li><strong>Stall</strong> — the loop is parked inside
 *       {@code hasNext()} and will not come back. We must give up
 *       after a bounded wait and tell the caller, otherwise the caller
 *       inherits the wedge.</li>
 *   <li><strong>Clean exhaustion without a terminal event</strong> —
 *       the stream finished (hasNext() returned false) but no
 *       {@link StreamEvent.RunEnd} was ever delivered. Not a stall,
 *       but still a contract violation: downstream code that keys off
 *       the terminal event (the desktop's {@code isStreaming} flag,
 *       the phase state machine) reads a dead run as a healthy one
 *       (the R735 "dead engine looks like a healthy green Idle"
 *       symptom).</li>
 * </ul>
 *
 * <p>{@link Outcome} distinguishes them so callers can react
 * differently: a stall is an error the parent must surface; clean
 * exhaustion only needs a synthetic terminal event delivered.
 *
 * <h2>Timeout source</h2>
 *
 * <p>Deliberately reuses {@code AETHERCODE_R744_FIRST_CHUNK_TIMEOUT_MS}
 * and its 180s default rather than minting a new knob. R744 already
 * established that budget as "the longest a healthy provider may take
 * before we call the attempt broken", measured at 40-90s cold-start
 * TTFB. R744 guards the producer's first chunk; this guards the
 * consumer's next event. A malformed override falls through to the
 * default for the same reason R744 does it: a typo in an env var must
 * never be able to disable the guard and reintroduce the wedge.
 *
 * <p>Stateless helper — not thread-safe as a state object. Call it
 * from one consumer thread at a time.
 */
public final class StreamConsumerGuard {

    private static final Logger LOG = LoggerFactory.getLogger(StreamConsumerGuard.class);

    /** R746: default bounded wait, identical to R744's producer-side
     *  first-chunk budget and R745's continuation-consumer budget. */
    public static final long TIMEOUT_MS_DEFAULT = 180_000L;

    /** R746: env var shared with R744/R745. See the class javadoc. */
    public static final String TIMEOUT_ENV = "AETHERCODE_R744_FIRST_CHUNK_TIMEOUT_MS";

    /** R746: poll granularity for the probe threads. Short enough
     *  that an R721 cancel ({@code Thread.interrupt()}) is observed
     *  promptly. */
    private static final long POLL_MS = 250L;

    /** R746: what the consumer loop ended up doing. */
    public enum Outcome {
        /** The stream was drained to exhaustion and DID deliver a
         *  {@link StreamEvent.RunEnd}. The happy path — no synthetic
         *  event was needed. */
        COMPLETED_WITH_RUN_END,
        /** The stream was drained to exhaustion but never delivered a
         *  {@code RunEnd}. {@link Result#syntheticRunEnd()} is non-null
         *  and the caller must deliver it to whatever is waiting. */
        EXHAUSTED_WITHOUT_RUN_END,
        /** The bounded wait expired with the producer still parked.
         *  {@link Result#syntheticRunEnd()} is non-null (a
         *  {@code consumer_timeout} terminal event) and the caller must
         *  both deliver it and surface the failure — the underlying
         *  stream is abandoned, not completed. */
        STALLED
    }

    /** R746: result of {@link #consume}. */
    public record Result(Outcome outcome, int eventCount, boolean sawRunEnd,
                         String stopReason, StreamEvent.RunEnd syntheticRunEnd) {
        public boolean stalled() { return outcome == Outcome.STALLED; }
        /** true when the caller must deliver {@link #syntheticRunEnd()}. */
        public boolean needsSyntheticRunEnd() { return syntheticRunEnd != null; }
    }

    private StreamConsumerGuard() {}

    /** R746: the bounded wait budget in milliseconds, from the shared
     *  env var, defaulting to {@link #TIMEOUT_MS_DEFAULT}. */
    public static long timeoutMs() {
        String v = System.getenv(TIMEOUT_ENV);
        if (v != null && !v.isBlank()) {
            try {
                long n = Long.parseLong(v.trim());
                if (n > 0) return n;
            } catch (NumberFormatException ignored) {
                LOG.warn("R746: ignoring unparseable {}={}", TIMEOUT_ENV, v);
            }
        }
        return TIMEOUT_MS_DEFAULT;
    }

    /**
     * R746: drain {@code it} through {@code onEvent}, with a bounded
     * wait per event and a terminal-event guarantee.
     *
     * <p>The handler runs on the calling thread. Any exception it
     * throws propagates unchanged to the caller (whose own
     * {@code catch (Throwable)} turns it into an error terminal event
     * — see R735a).
     *
     * @param site       a short label used in the LOG markers, e.g.
     *                   {@code "subagent-orchestrator"}. Appears in the
     *                   daemon log so a stalled consumer is provable
     *                   post-mortem (Lesson 1052).
     * @param it         the stream iterator
     * @param onEvent    per-event handler, including {@code RunEnd}
     * @param runContext extra context folded into the LOG markers
     *                   (run id, agent name, phase …). May be null.
     * @param timeoutMs  the bounded wait per event. Production callers
     *                   pass {@link #timeoutMs()}; unit tests pass a
     *                   short value so the stall path finishes fast.
     */
    public static Result consume(String site, Iterator<StreamEvent> it,
                                 Consumer<StreamEvent> onEvent,
                                 String runContext, long timeoutMs) {
        int eventCount = 0;
        while (true) {
            Probe probe = probeHasNext(it, timeoutMs);
            if (probe == Probe.STALLED) {
                // The event count rides along in the stopReason, not
                // only in the LOG line: the callers that surface a
                // stall to a downstream waiter (the subagent
                // orchestrator's error Result, the CLI's stderr
                // message) need to say how far the run got, and a
                // stopReason that omits it would force them to log
                // and scrape.
                String stopReason = "consumer_timeout: no stream event within "
                        + timeoutMs + "ms (" + eventCount + " event(s) received)";
                LOG.warn("R746: {} consumer stalled — no stream event within {}ms "
                                + "after {} event(s){}. Abandoning the stream and minting "
                                + "a consumer_timeout terminal event.",
                        site, timeoutMs, eventCount, ctx(runContext));
                return new Result(Outcome.STALLED, eventCount, false, stopReason,
                        new StreamEvent.RunEnd(stopReason, java.util.List.of()));
            }
            if (probe == Probe.CLEAN_DRAIN) {
                break;
            }
            StreamEvent ev = it.next();
            eventCount++;
            onEvent.accept(ev);
            if (ev instanceof StreamEvent.RunEnd re) {
                return new Result(Outcome.COMPLETED_WITH_RUN_END, eventCount, true,
                        re.stopReason() == null ? "end_turn" : re.stopReason(), null);
            }
        }
        // Ran out of elements without a RunEnd. R735's terminal-event
        // contract, enforced on the CONSUMER side.
        String stopReason = "no_run_end: consumer exited without terminal event";
        LOG.warn("R746: {} consumer exited without RunEnd after {} event(s){} — "
                        + "emitting a synthetic terminal event",
                site, eventCount, ctx(runContext));
        return new Result(Outcome.EXHAUSTED_WITHOUT_RUN_END, eventCount, false,
                stopReason, new StreamEvent.RunEnd(stopReason, java.util.List.of()));
    }

    private static String ctx(String runContext) {
        return (runContext == null || runContext.isBlank()) ? "" : ", " + runContext;
    }

    /** R746: the three possible answers to "is there another event?". */
    private enum Probe { HAVE_MORE, CLEAN_DRAIN, STALLED }

    /**
     * R746: bounded {@code hasNext()}, returning which of the three
     * states we ended up in.
     *
     * <p>{@code hasNext()} is itself a blocking read, so it cannot be
     * polled from the calling thread. We run it on a short-lived daemon
     * probe thread and {@code join()} it with a deadline, which keeps
     * the original blocking semantics (no busy-wait, no truncated
     * stream) while giving the caller a bound.
     *
     * <ul>
     *   <li>{@link Probe#HAVE_MORE} — answered {@code true} inside the
     *       budget; the caller may call {@code next()}.</li>
     *   <li>{@link Probe#CLEAN_DRAIN} — answered {@code false} inside
     *       the budget; the stream finished normally. This is NOT a
     *       stall, and conflating the two would make every healthy
     *       short stream look like a timeout.</li>
     *   <li>{@link Probe#STALLED} — the deadline expired with the
     *       probe still parked: the producer is wedged.</li>
     * </ul>
     *
     * <p>On stall the probe is interrupted and abandoned; it stays a
     * daemon so a genuinely dead provider cannot keep the JVM alive.
     */
    private static Probe probeHasNext(Iterator<?> it, long budgetMs) {
        final boolean[] answer = { false };
        // R746 fix: carry the probe's Throwable across the thread
        // boundary so it can be rethrown ON THE CALLING THREAD.
        //
        // The earlier version re-invoked `it.hasNext()` on the calling
        // thread after the probe settled, to let a provider exception
        // propagate. That safety re-run DEFEATED THE ENTIRE BOUND: on
        // an iterator that has no cached answer — i.e. exactly the
        // wedged producer this helper exists to catch — the second
        // call blocks forever on the calling thread. The probe
        // returned STALLED correctly and the consumer thread parked
        // anyway. Capturing and rethrowing keeps the exception type
        // (R735a needs the real cause for its error RunEnd) without
        // ever re-entering the blocking read.
        final Throwable[] thrown = { null };
        Runnable probe = () -> {
            try {
                answer[0] = it.hasNext();
            } catch (Throwable t) {
                answer[0] = false;
                thrown[0] = t;
            }
        };
        Thread probeThread = new Thread(probe, "aethercode-consumer-hasnext-probe");
        probeThread.setDaemon(true);
        probeThread.start();
        long deadline = System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(budgetMs);
        while (System.nanoTime() < deadline) {
            try {
                probeThread.join(POLL_MS);
            } catch (InterruptedException ie) {
                // R721: a cancel interrupted us. Abandon the probe and
                // let the interrupt propagate so the run's finally
                // (lock release) still executes.
                probeThread.interrupt();
                Thread.currentThread().interrupt();
                return Probe.STALLED;
            }
            if (probeThread.isAlive()) {
                continue;
            }
            // Probe settled without wedging: safe to surface whatever
            // it saw. Rethrowing on THIS thread preserves the original
            // exception type for the caller's catch (Throwable).
            if (thrown[0] != null) {
                throw rethrow(thrown[0]);
            }
            return answer[0] ? Probe.HAVE_MORE : Probe.CLEAN_DRAIN;
        }
        probeThread.interrupt();
        return Probe.STALLED;
    }

    /** R746: rethrow a Throwable captured on the probe thread without
     *  the compiler complaining about the undeclared type. */
    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        if (t instanceof Error err) throw err;
        return new RuntimeException(t);
    }

    /**
     * R746: bounded {@code hasNext()} in the R745 shape — "may the
     * caller call {@code next()}?" — where a clean drain and a stall
     * both answer {@code false}.
     *
     * <p>Kept as the public primitive the R745 continuation dispatcher
     * calls; {@link #consume} uses {@link #probeHasNext} directly
     * because it needs the clean-drain / stall distinction.
     *
     * <p>NOTE the deliberate asymmetry with {@link #consume}: this
     * two-valued view folds "drained" into "don't call next()", which
     * is what the dispatcher's loop wants — it calls
     * {@code it.next()} only when this returns true, so a drained
     * iterator exits the loop cleanly instead of throwing
     * {@code NoSuchElementException} out of the loop body. Do not use
     * this to distinguish a stall from a clean drain; that is what
     * {@link #consume}'s {@link Outcome} is for.
     */
    public static boolean awaitNextEvent(Iterator<?> it, long budgetMs) {
        return probeHasNext(it, budgetMs) == Probe.HAVE_MORE;
    }
}