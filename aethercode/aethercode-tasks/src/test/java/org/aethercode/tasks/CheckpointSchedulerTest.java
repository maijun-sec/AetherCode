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

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link CheckpointScheduler}. Verifies the
 * callback fires at the configured interval and that errors
 * are counted but don't propagate.
 */
class CheckpointSchedulerTest {

    @Test
    void tickNow_firesCallback() {
        AtomicInteger count = new AtomicInteger(0);
        CheckpointScheduler s = new CheckpointScheduler(60_000L, count::incrementAndGet);
        s.tickNow();
        assertEquals(1, count.get());
        assertEquals(1, s.invocations());
        s.tickNow();
        s.tickNow();
        assertEquals(3, count.get());
        assertEquals(3, s.invocations());
    }

    @Test
    void errorsAreCounted() {
        CheckpointScheduler s = new CheckpointScheduler(60_000L, () -> {
            throw new RuntimeException("boom");
        });
        s.tickNow();
        s.tickNow();
        assertEquals(2, s.invocations());
        assertEquals(2, s.failures(), "errors should be counted but not propagated");
    }

    @Test
    void constructor_rejectsBadArgs() {
        assertThrows(IllegalArgumentException.class,
                () -> new CheckpointScheduler(50L, () -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> new CheckpointScheduler(60_000L, (Runnable) null));
    }

    @Test
    void startStop_isIdempotent() {
        CheckpointScheduler s = new CheckpointScheduler(60_000L, () -> {});
        s.start();
        s.start(); // no-op
        s.stop();
        s.stop(); // no-op
    }
}
