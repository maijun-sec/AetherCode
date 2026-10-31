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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link TaskWatchdog}. Uses a small maxAge so
 * tests can drive the watchdog without long sleeps.
 */
class TaskWatchdogTest {

    private TaskRegistry registry;
    private TaskWatchdog watchdog;

    @BeforeEach
    void setUp() {
        registry = TaskRegistry.instance();
        registry.resetForTests();
        // 100ms timeout — short enough for fast tests.
        watchdog = new TaskWatchdog(registry, 100L, 20L, null);
    }

    @AfterEach
    void tearDown() {
        if (watchdog != null) watchdog.stop();
        registry.resetForTests();
    }

    @Test
    void killsOverdueTask() throws Exception {
        Task t = registry.create(TaskType.AGENT, "old", null);
        registry.updateStatus(t.id(), TaskStatus.RUNNING);
        // Wait so the task is older than maxAge (100ms).
        Thread.sleep(150);
        int killed = watchdog.tickNow();
        assertEquals(1, killed, "1 overdue task should be killed");
        assertEquals(TaskStatus.KILLED, registry.get(t.id()).orElseThrow().status());
        assertEquals(1, watchdog.killsTriggered());
    }

    @Test
    void doesNotKillRecentTask() throws Exception {
        Task t = registry.create(TaskType.AGENT, "young", null);
        registry.updateStatus(t.id(), TaskStatus.RUNNING);
        // No sleep — task is fresh.
        int killed = watchdog.tickNow();
        assertEquals(0, killed);
        assertEquals(TaskStatus.RUNNING, registry.get(t.id()).orElseThrow().status());
    }

    @Test
    void onKillCallbackReceivesTaskId() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        watchdog.stop();
        watchdog = new TaskWatchdog(registry, 50L, 20L, captured::set);
        Task t = registry.create(TaskType.AGENT, "cb", null);
        registry.updateStatus(t.id(), TaskStatus.RUNNING);
        Thread.sleep(100);
        watchdog.tickNow();
        assertEquals(t.id(), captured.get());
    }

    @Test
    void startStopIsIdempotent() {
        watchdog.start();
        watchdog.start(); // no-op
        watchdog.stop();
        watchdog.stop(); // no-op
    }

    @Test
    void constructor_rejectsBadArgs() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskWatchdog(null, 1000L));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskWatchdog(registry, 10L));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskWatchdog(registry, 1000L, 5L, null));
    }
}
