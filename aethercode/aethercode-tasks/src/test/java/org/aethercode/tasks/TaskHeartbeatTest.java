package org.aethercode.tasks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link TaskHeartbeat}. Verifies that the
 * periodic tick fires and emits heartbeat events for RUNNING
 * tasks.
 */
class TaskHeartbeatTest {

    private TaskRegistry registry;
    private TaskHeartbeat heartbeat;

    @BeforeEach
    void setUp() {
        registry = TaskRegistry.instance();
        registry.resetForTests();
    }

    @AfterEach
    void tearDown() {
        if (heartbeat != null) heartbeat.stop();
        registry.resetForTests();
    }

    @Test
    void tickEmitsHeartbeatForRunningTask() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Task t = registry.create(TaskType.AGENT, "running", null);
        registry.updateStatus(t.id(), TaskStatus.RUNNING);

        heartbeat = new TaskHeartbeat(registry, 50L, ev -> {
            if (t.id().equals(ev.taskId())) latch.countDown();
        });
        heartbeat.start();
        assertTrue(latch.await(2, TimeUnit.SECONDS),
                "heartbeat should fire for the running task within 2s");
    }

    @Test
    void onlyRunningTasksGetHeartbeats() throws Exception {
        AtomicInteger runningCount = new AtomicInteger(0);
        AtomicInteger pendingCount = new AtomicInteger(0);
        Task running = registry.create(TaskType.AGENT, "run", null);
        registry.updateStatus(running.id(), TaskStatus.RUNNING);
        Task pending = registry.create(TaskType.AGENT, "wait", null);
        // pending stays PENDING

        heartbeat = new TaskHeartbeat(registry, 30L, ev -> {
            if (running.id().equals(ev.taskId())) runningCount.incrementAndGet();
            if (pending.id().equals(ev.taskId())) pendingCount.incrementAndGet();
        });
        heartbeat.start();
        // Poll for up to 2s instead of a fixed 150ms sleep. CI load
        // can starve the daemon scheduler.
        long deadline = System.currentTimeMillis() + 2_000L;
        while (runningCount.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(runningCount.get() > 0, "RUNNING task should get heartbeats");
        assertEquals(0, pendingCount.get(), "PENDING task should not get heartbeats");
    }

    @Test
    void constructor_rejectsBadArgs() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskHeartbeat(null, 1000L, ev -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskHeartbeat(registry, 10L, ev -> {}));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskHeartbeat(registry, 1000L, null));
    }

    @Test
    void runningTaskIds_reflectsCurrentState() throws Exception {
        heartbeat = new TaskHeartbeat(registry, 30L, ev -> {});
        // Before start: empty.
        assertTrue(heartbeat.runningTaskIds().isEmpty());
        heartbeat.start();
        Task t1 = registry.create(TaskType.AGENT, "t1", null);
        registry.updateStatus(t1.id(), TaskStatus.RUNNING);
        Task t2 = registry.create(TaskType.AGENT, "t2", null);
        // Wait for at least one tick.
        Thread.sleep(80);
        assertEquals(1, heartbeat.runningTaskIds().size());
        assertTrue(heartbeat.runningTaskIds().contains(t1.id()));
        assertFalse(heartbeat.runningTaskIds().contains(t2.id()));
    }
}
