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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link TaskSupervisor}. Verifies the
 * combined watchdog + heartbeat behavior in a single daemon.
 */
class TaskSupervisorTest {

    private TaskRegistry registry;
    private TaskSupervisor supervisor;

    @BeforeEach
    void setUp() {
        registry = TaskRegistry.instance();
        registry.resetForTests();
    }

    @AfterEach
    void tearDown() {
        if (supervisor != null) supervisor.stop();
        registry.resetForTests();
    }

    @Test
    void tickEmitsHeartbeatAndKillsOverdue() throws Exception {
        AtomicInteger killCount = new AtomicInteger(0);
        supervisor = new TaskSupervisor(registry, 100L, 30L,
                hb -> {},
                id -> killCount.incrementAndGet());
        // Create a task and let it age beyond maxAge.
        Task t = registry.create(TaskType.AGENT, "stale", null);
        registry.updateStatus(t.id(), TaskStatus.RUNNING);
        Thread.sleep(120);
        int killed = supervisor.tickNow();
        assertEquals(1, killed, "1 overdue task should be killed");
        assertEquals(1, killCount.get());
        assertEquals(TaskStatus.KILLED, registry.get(t.id()).orElseThrow().status());
    }

    @Test
    void runningTaskIdsReflectsLiveTasks() throws Exception {
        supervisor = new TaskSupervisor(registry, 60_000L, 20L, hb -> {}, null);
        // Drive the tick manually instead of relying on the daemon
        // scheduler. On slow CI the 60ms wait below can race with the
        // 20ms first-tick delay, leaving the snapshot empty.
        Task t = registry.create(TaskType.AGENT, "alive", null);
        registry.updateStatus(t.id(), TaskStatus.RUNNING);
        supervisor.tickNow();
        assertTrue(supervisor.runningTaskIds().contains(t.id()));
    }
}
