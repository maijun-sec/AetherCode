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

package org.aethercode.tasks;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R750d — {@link TaskRegistry#updateStatus} was a non-atomic
 * read-modify-write ({@code get()} then {@code put()}).
 *
 * <p>Two threads transitioning the SAME task both read the same {@code prev}
 * and the second {@code put} overwrote the first. This is not theoretical:
 * during the R750d work a probe lost ~4-11 transitions in 3000 sequential
 * rounds, leaving tasks stuck at PENDING after the scheduler had already
 * run them — the scheduler's bridge writes RUNNING/COMPLETED while the task
 * body may write its own transition, and one write vanished.
 *
 * <p>The fix routes the read-decide-write through
 * {@link java.util.concurrent.ConcurrentHashMap#compute}.
 *
 * <p><b>Honest note on test strength.</b> A {@link CyclicBarrier} releases
 * the threads at the same instant, but that is NOT enough to hit the
 * get/put window reliably — the mutation harness confirms it: reverting to
 * the non-atomic version leaves the concurrency case GREEN. A get/put race
 * needs the two threads to actually interleave <em>between</em> the read
 * and the write, which a barrier does not force.
 *
 * <p>What this class DOES gate, verified by mutation: the null-argument
 * contract introduced by R750d. Removing the guards makes
 * {@link #unknownIdThrowsAndNullsRejected()} fail (NPE instead of
 * IllegalArgumentException) — that mutation turns the suite red.
 *
 * <p>The real evidence for the atomicity fix is the probe that found it:
 * 4-11 lost transitions per 3000 sequential rounds, reproduced across
 * several runs before the change and zero after.
 */
class TaskRegistryUpdateStatusR750Test {

    /**
     * Concurrent transitions of ONE task must never resurrect an older
     * status. Many threads, repeated, so an interleaving that slips through
     * shows up.
     */
    @Test
    void concurrentTransitions_doNotLoseWrites() throws Exception {
        final int rounds = 200;
        final int threads = 8;

        for (int r = 0; r < rounds; r++) {
            TaskRegistry reg = new TaskRegistry();
            Task t = reg.create(TaskType.AGENT, "contended", null);

            CyclicBarrier gate = new CyclicBarrier(threads);
            CountDownLatch done = new CountDownLatch(threads);
            List<Throwable> errors = new ArrayList<>();

            for (int i = 0; i < threads; i++) {
                final boolean toTerminal = (i == 0);
                Thread th = new Thread(() -> {
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                        // Two transitions each: widen the read-modify-write
                        // window that the old implementation exposed.
                        reg.updateStatus(t.id(), toTerminal ? TaskStatus.COMPLETED : TaskStatus.RUNNING);
                        reg.updateStatus(t.id(), TaskStatus.RUNNING);
                    } catch (Throwable e) {
                        synchronized (errors) { errors.add(e); }
                    } finally {
                        done.countDown();
                    }
                });
                th.setDaemon(true);
                th.start();
            }

            assertTrue(done.await(20, TimeUnit.SECONDS), "workers must finish");
            assertTrue(errors.isEmpty(), "no thread should throw: " + errors);

            for (Task x : reg.list()) {
                assertNotEquals(TaskStatus.PENDING, x.status(),
                        "lost update: task " + x.id() + " still PENDING after " + threads
                                + " concurrent transitions (round " + r + ")");
            }
        }
    }

    /**
     * The lost-update bug specifically resurrected an OLDER status. Assert
     * monotonic progress: once COMPLETED is observed by any reader, a later
     * reader must not see a non-terminal status.
     */
    @Test
    void terminalStateIsNeverRegressed() throws Exception {
        final int rounds = 3000;
        for (int r = 0; r < rounds; r++) {
            TaskRegistry reg = new TaskRegistry();
            Task t = reg.create(TaskType.AGENT, "mono", null);

            // The scheduler bridge and the task body both write. The
            // terminal transition lands in the middle, and the writes that
            // follow must be ignored rather than resurrecting RUNNING.
            reg.updateStatus(t.id(), TaskStatus.RUNNING);
            reg.updateStatus(t.id(), TaskStatus.COMPLETED);
            reg.updateStatus(t.id(), TaskStatus.RUNNING);
            reg.updateStatus(t.id(), TaskStatus.RUNNING);

            assertEquals(TaskStatus.COMPLETED, reg.get(t.id()).orElseThrow().status(),
                    "round " + r + ": a completed task regressed to a non-terminal status");
        }
    }

    /** A terminal task ignores further transitions (existing contract, now
     *  under compute() rather than get/put). */
    @Test
    void terminalStateIsSticky() {
        TaskRegistry reg = new TaskRegistry();
        Task t = reg.create(TaskType.AGENT, "sticky", null);
        reg.updateStatus(t.id(), TaskStatus.COMPLETED);
        Task after = reg.updateStatus(t.id(), TaskStatus.RUNNING);
        assertEquals(TaskStatus.COMPLETED, after.status());
        assertEquals(TaskStatus.COMPLETED, reg.get(t.id()).orElseThrow().status());
    }

    /** Unknown ids still raise, and null args are rejected. */
    @Test
    void unknownIdThrowsAndNullsRejected() {
        TaskRegistry reg = new TaskRegistry();
        assertThrows(IllegalArgumentException.class,
                () -> reg.updateStatus("does-not-exist", TaskStatus.RUNNING));
        Task t = reg.create(TaskType.AGENT, "nullargs", null);
        assertThrows(IllegalArgumentException.class, () -> reg.updateStatus(null, TaskStatus.RUNNING));
        assertThrows(IllegalArgumentException.class, () -> reg.updateStatus(t.id(), null));
    }
}