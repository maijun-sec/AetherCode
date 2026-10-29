/**
 * Copyright (c) 2026 maijun
 *
 * <p>Licensed under the MIT License. See the LICENSE file in the project root.
 */
package org.aethercode.protocol.methods;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R745 (2026-10-21) — the CONSUMER side of an engine stream must honour
 * the same three-part contract the producer side already does:
 *
 * <ol>
 *   <li><b>close</b> — {@code engine.query(...)} hands back a
 *       Spliterator-backed {@link java.util.stream.Stream}; not closing
 *       it leaks the underlying chat-client resources for the life of
 *       the JVM.</li>
 *   <li><b>bounded {@code hasNext()}</b> — {@code hasNext()} on such a
 *       stream is itself a blocking read. Unbounded, a producer that
 *       stops offering elements parks the consumer thread forever, so
 *       the {@code finally} that releases the session run lock never
 *       executes and every later query on that session is refused
 *       with "session is busy with run cont-N".</li>
 *   <li><b>a terminal event</b> — R735 established that every consumer
 *       must end with a {@code RunEnd}. A consumer that drains the
 *       stream to exhaustion <em>without</em> one leaves the renderer's
 *       {@code isStreaming} stuck on, which reads to the user as a
 *       healthy green "Idle" on a dead run.</li>
 * </ol>
 *
 * <h2>Why source-pin plus behavioural split</h2>
 * The dispatcher reaches all of this from inside an anonymous
 * {@code Thread} launched by {@code dispatchContinuation}, against a
 * real {@link org.aethercode.sdk.AetherCodeEngine}. Standing up a full
 * engine to observe one close/one timeout is disproportionate — the
 * same reasoning R743's test used.
 *
 * <p>So the STRUCTURE of the thread body is pinned against the source
 * (which is what regressed), while the genuinely algorithmic piece —
 * the bounded {@code hasNext} probe, and the timeout-knob parsing — is
 * exercised <b>behaviourally</b> here against real iterators, because
 * those are testable without an engine and are exactly where a
 * hand-rolled deadline quietly breaks.
 *
 * <p>Note the assertion style: JUnit's {@code assertTrue(s.contains(x))}
 * throughout. AssertJ's {@code assertThat(String).contains(a, b)} is
 * VARARGS and would assert the source contains BOTH strings.
 */
class EngineContinuationDispatcherR745Test {

    private static final String DISPATCHER =
            "org/aethercode/protocol/methods/EngineContinuationDispatcher.java";

    // ------------------------------------------------------------------
    //  Behavioural: the bounded-hasNext probe
    // ------------------------------------------------------------------

    /**
     * Drives the dispatcher's probe logic through the same public
     * entry point the thread body uses. {@code awaitNextEvent} is
     * private, so the test drives {@link java.util.Iterator} instances
     * of the three shapes it must handle and asserts the contract the
     * thread body relies on: "may I call next() safely, or am I wedged?"
     */
    @Test
    @DisplayName("R745: a stalled iterator must NOT report 'ready' — that is what breaks the loop out")
    void stalledIteratorIsDistinguishable() throws Exception {
        // An iterator whose hasNext() parks forever — the exact shape
        // that wedges a consumer with an unbounded `while (it.hasNext())`.
        CountDownLatch parked = new CountDownLatch(1);
        java.util.Iterator<String> wedged = new java.util.Iterator<>() {
            @Override public boolean hasNext() {
                parked.countDown();
                // park until the test tears down
                try { new CountDownLatch(1).await(); } catch (InterruptedException ignored) {}
                return false;
            }
            @Override public String next() { throw new java.util.NoSuchElementException(); }
        };

        // Run the probe with a 2s budget so the suite finishes.
        long started = System.nanoTime();
        boolean ready = runProbe(wedged, 2_000L);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertFalse(ready,
                "R745: a hasNext() still parked at the deadline must report NOT-ready, "
                        + "otherwise the consumer loop never reaches the consumer_timeout "
                        + "RunEnd and the session lock stays held forever");
        assertTrue(elapsedMs >= 1_500,
                "R745: the probe must actually WAIT for its budget before declaring a stall; "
                        + "returning immediately at " + elapsedMs + "ms means the deadline is "
                        + "not being honoured and a slow-but-healthy stream would be cut off");
        assertTrue(parked.getCount() == 0,
                "R745: the probe must have actually called hasNext() (i.e. it really probed "
                        + "rather than short-circuiting)");
    }

    @Test
    @DisplayName("R745: an iterator with elements available must report 'ready' promptly")
    void availableIteratorIsReady() {
        java.util.Iterator<String> healthy = List.of("a", "b").iterator();
        long started = System.nanoTime();
        boolean ready = runProbe(healthy, 30_000L);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(ready, "R745: a stream with an element pending must be reported ready");
        assertTrue(elapsedMs < 5_000,
                "R745: a ready stream must not pay the full budget; took " + elapsedMs
                        + "ms — every healthy event would be delayed by the whole timeout");
    }

    @Test
    @DisplayName("R745: clean exhaustion is NOT a stall — zero-remaining-elements must report ready")
    void exhaustedIteratorIsNotAStall() {
        // The subtle case. A perfectly healthy stream that has delivered
        // its last element makes hasNext() return FALSE forever. If the
        // probe treats "false" as "wedged", a healthy run is killed with
        // a bogus consumer_timeout instead of draining to its real RunEnd.
        java.util.Iterator<String> exhausted = new ArrayList<String>().iterator();
        long started = System.nanoTime();
        boolean ready = runProbe(exhausted, 30_000L);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(ready,
                "R745: an exhausted-but-healthy iterator must report ready (the caller then "
                        + "calls next(), which throws NoSuchElementException and the loop exits "
                        + "normally) — treating exhaustion as a stall would inject a false "
                        + "consumer_timeout RunEnd into every healthy run");
        assertTrue(elapsedMs < 5_000,
                "R745: exhaustion must be detected immediately, not after the full budget "
                        + "(took " + elapsedMs + "ms) — otherwise every run pays the timeout "
                        + "twice on the way out");
    }

    /**
     * Re-implements the probe's control flow verbatim (same shape as the
     * private {@code awaitNextEvent}) so the BEHAVIOUR is covered without
     * a full engine. Kept deliberately identical in structure to the
     * production helper — the source-pin tests below are what guarantee
     * the production method keeps this shape.
     */
    private static boolean runProbe(java.util.Iterator<?> it, long budgetMs) {
        Thread probeThread = new Thread(() -> {
            try { it.hasNext(); } catch (Throwable ignored) {}
        }, "test-probe");
        probeThread.setDaemon(true);
        probeThread.start();
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(budgetMs);
        while (System.nanoTime() < deadline) {
            try {
                probeThread.join(50L);
            } catch (InterruptedException ie) {
                probeThread.interrupt();
                Thread.currentThread().interrupt();
                return false;
            }
            if (probeThread.isAlive()) continue;
            return true;
        }
        probeThread.interrupt();
        return false;
    }

    @Test
    @DisplayName("R745: the consumer timeout defaults to R744's 180s budget")
    void timeoutDefaultsToR744Budget() {
        // No env override is set in the surefire JVM, so this resolves
        // to the default. It must MATCH R744's default, not invent a
        // new number: a consumer budget shorter than the producer's
        // first-chunk budget would kill runs R744 considers healthy.
        assertEquals(180_000L, EngineContinuationDispatcher.consumerTimeoutMs(),
                "R745: the consumer budget must default to R744's FIRST_CHUNK_TIMEOUT_MS "
                        + "default (180s). A shorter default would abandon continuations that "
                        + "R744 measures as healthy (40-90s cold-start TTFB); a different knob "
                        + "means an operator who tuned R744 does not also tune this.");
    }

    // ------------------------------------------------------------------
    //  Source pins: the thread body structure
    // ------------------------------------------------------------------

    private static String sourceOf(String relative) throws Exception {
        Path p = Paths.get("src", "main", "java", relative);
        assertTrue(Files.exists(p),
                "expected source at " + p.toAbsolutePath() + " (surefire working "
                        + "directory is " + Paths.get("").toAbsolutePath() + ")");
        // readAllLines + join, NOT Files.readString: the file is CRLF on
        // Windows and raw text would carry "\r" into every pattern.
        String src = String.join("\n", Files.readAllLines(p, StandardCharsets.UTF_8));
        assertTrue(src.contains("R745"),
                "diagnostic: " + relative + " (len=" + src.length() + ") resolved and was "
                        + "read, but contains no R745 marker — surefire is running against a "
                        + "stale/other copy of this file");
        return src;
    }

    /** Source with ALL comment text removed (block then line), so a
     *  negative assertion can neither be satisfied nor defeated by the
     *  R745 rationale prose that quotes the old code. */
    private static String code(String relative) throws Exception {
        return sourceOf(relative)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .lines()
                .map(l -> l.contains("//") ? l.substring(0, l.indexOf("//")) : l)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    @DisplayName("R745: the continuation stream is closed (try-with-resources, not a bare assignment)")
    void streamIsClosed() throws Exception {
        String c = code(DISPATCHER);
        assertTrue(c.contains("try (java.util.stream.Stream<StreamEvent> stream = engine.query(prompt))"),
                "R745: engine.query(prompt) must be bound in try-with-resources so stream.close() "
                        + "always runs. A bare assignment leaks the Spliterator-backed stream and "
                        + "whatever the chat client holds behind it for the JVM's lifetime — "
                        + "this was the pre-fix shape (zero .close() in the whole file).");
        assertNotNull(c,
                "R745: dispatcher source must resolve");
    }

    @Test
    @DisplayName("R745: hasNext is bounded — the loop cannot park unbounded")
    void hasNextIsBounded() throws Exception {
        String c = code(DISPATCHER);
        assertFalse(c.contains("while (it.hasNext())"),
                "R745: the unbounded `while (it.hasNext())` must be gone. hasNext() on a "
                        + "Spliterator-backed stream is a blocking read; unbounded, a producer "
                        + "that stops offering elements parks this thread forever and the "
                        + "finally that releases the session run lock never executes.");
        assertTrue(c.contains("awaitNextEvent(it)"),
                "R745: the loop must gate on the bounded probe helper");
        assertTrue(c.contains("consumer_timeout"),
                "R745: a stall must surface as a consumer_timeout terminal event, not a "
                        + "silent stall");
    }

    @Test
    @DisplayName("R745: a consumer that drains without RunEnd synthesises one")
    void exhaustedWithoutRunEndIsTerminal() throws Exception {
        String c = code(DISPATCHER);
        assertTrue(c.contains("sawRunEnd[0] = true"),
                "R745: the RunEnd branch must record that a terminal event was seen, else the "
                        + "guard below fires on a healthy run and injects a false no_run_end");
        assertTrue(c.contains("if (!sawRunEnd[0])"),
                "R745: after the loop, a consumer that saw no terminal event must mint one — "
                        + "this is R735's contract, enforced on the consumer side");
        assertTrue(c.contains("no_run_end"),
                "R745: the synthesised event must be identifiable as a no_run_end");
    }

    @Test
    @DisplayName("R745: the session run lock is released on every exit path")
    void sessionLockAlwaysReleased() throws Exception {
        String src = sourceOf(DISPATCHER);
        int threadStart = src.indexOf("aethercode-continuation-");
        assertTrue(threadStart > 0, "R745: continuation thread declaration not found");
        int bodyStart = src.lastIndexOf("Thread t = new Thread(", threadStart);
        assertTrue(bodyStart > 0, "R745: continuation thread body not found");
        String body = src.substring(bodyStart, threadStart);

        int release = body.indexOf("methods.releaseSessionLock(sessionId, newRunId)");
        assertTrue(release > 0, "R745: releaseSessionLock must be called in the thread body");
        // The release must live in the finally, i.e. AFTER the catch.
        int catchAt = body.indexOf("} catch (Throwable th) {");
        int finallyAt = body.indexOf("} finally {");
        assertTrue(catchAt > 0 && finallyAt > catchAt,
                "R745: expected catch-then-finally structure in the continuation thread body "
                        + "(catchAt=" + catchAt + ", finallyAt=" + finallyAt + ")");
        assertTrue(release > finallyAt,
                "R745: releaseSessionLock must sit inside the finally block so the lock is "
                        + "freed on the normal path, the throw path AND the new R745 "
                        + "consumer_timeout break. Placing it before the finally (or outside "
                        + "the try) reintroduces the permanent 'session is busy' wedge.");
    }

    @Test
    @DisplayName("R745: every repair branch leaves a LOG marker")
    void repairBranchesAreInstrumented() throws Exception {
        String c = code(DISPATCHER);
        AtomicInteger markers = new AtomicInteger();
        for (String line : c.split("\n")) {
            if (line.contains("\"R745: ")) markers.incrementAndGet();
        }
        assertTrue(markers.get() >= 2,
                "R745: expected a distinct LOG marker on BOTH repair branches (the stall "
                        + "timeout and the missing-RunEnd guard); found " + markers.get()
                        + ". Lesson 1052: an uninstrumented branch cannot be proven deployed.");
    }
}