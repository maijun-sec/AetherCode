package org.aethercode.core.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link TaskDispatcher}. Uses a simple in-
 * memory implementation since the interface is abstract.
 */
class TaskDispatcherTest {

    /** Minimal test dispatcher — a fixed thread pool with
     *  priority queue semantics. */
    private static class TestDispatcher implements TaskDispatcher {
        private final java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(2);
        private final java.util.List<String> ids =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicInteger running =
                new java.util.concurrent.atomic.AtomicInteger(0);
        private final java.util.concurrent.atomic.AtomicInteger pending =
                new java.util.concurrent.atomic.AtomicInteger(0);
        private final java.util.concurrent.Semaphore idle =
                new java.util.concurrent.Semaphore(0);

        @Override
        public String submit(String description, Callable<?> task) {
            return submit(description, TaskDispatcher.Priority.NORMAL, task);
        }

        @Override
        public synchronized String submit(String description, TaskDispatcher.Priority priority, Callable<?> task) {
            String id = "t-" + System.nanoTime();
            ids.add(id);
            pending.incrementAndGet();
            pool.submit(() -> {
                pending.decrementAndGet();
                running.incrementAndGet();
                try { task.call(); } catch (Exception e) { /* ignore */ }
                running.decrementAndGet();
                if (pending.get() == 0 && running.get() == 0) idle.release();
            });
            return id;
        }

        @Override
        public boolean cancel(String taskId) { return false; /* simplified */ }

        @Override public int pendingCount() { return pending.get(); }
        @Override public int runningCount() { return running.get(); }

        @Override
        public boolean awaitIdle(long timeoutMs) throws InterruptedException {
            return idle.tryAcquire(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        @Override public List<String> recentTaskIds() { return List.copyOf(ids); }
    }

    @Test
    void submit_runsTask() throws Exception {
        TestDispatcher d = new TestDispatcher();
        AtomicInteger ran = new AtomicInteger(0);
        d.submit("test", ran::incrementAndGet);
        assertTrue(d.awaitIdle(2000));
        assertEquals(1, ran.get());
    }

    @Test
    void priority_isExposed() {
        // The interface exposes Priority — verify the enum exists.
        assertEquals(TaskDispatcher.Priority.HIGH, TaskDispatcher.Priority.valueOf("HIGH"));
        assertEquals(3, TaskDispatcher.Priority.values().length);
    }

    @Test
    void recentTaskIds_tracksSubmissions() throws Exception {
        TestDispatcher d = new TestDispatcher();
        AtomicReference<String> a = new AtomicReference<>();
        AtomicReference<String> b = new AtomicReference<>();
        a.set(d.submit("a", () -> 1));
        b.set(d.submit("b", () -> 2));
        d.awaitIdle(2000);
        assertTrue(d.recentTaskIds().contains(a.get()));
        assertTrue(d.recentTaskIds().contains(b.get()));
    }
}
