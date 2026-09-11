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
