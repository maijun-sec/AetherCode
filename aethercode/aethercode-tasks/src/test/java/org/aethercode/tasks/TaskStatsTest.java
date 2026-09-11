package org.aethercode.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link TaskStats}. Symmetric with PlanStats.
 */
class TaskStatsTest {

    private static Task task(String id, TaskType type, TaskStatus status, String parent) {
        return new Task(id, type, status, "desc-" + id, parent, System.currentTimeMillis(), 0L);
    }

    @Test
    void emptyRegistry_returnsEmptyStats() {
        TaskStats s = TaskStats.from(new TaskRegistry());
        assertEquals(0, s.total());
        assertEquals("no tasks", s.summary());
    }

    @Test
    void nullTasks_returnsEmptyStats() {
        TaskStats s = TaskStats.from((List<Task>) null);
        assertEquals(0, s.total());
    }

    @Test
    void countsByStatus() {
        List<Task> tasks = List.of(
                task("u-1", TaskType.USER, TaskStatus.RUNNING, null),
                task("a-1", TaskType.AGENT, TaskStatus.PENDING, "u-1"),
                task("a-2", TaskType.AGENT, TaskStatus.COMPLETED, "u-1"),
                task("a-3", TaskType.AGENT, TaskStatus.FAILED, "u-1"),
                task("a-4", TaskType.AGENT, TaskStatus.KILLED, "u-1")
        );
        TaskStats s = TaskStats.from(tasks);
        assertEquals(5, s.total());
        assertEquals(1, s.running());
        assertEquals(1, s.pending());
        assertEquals(1, s.completed());
        assertEquals(1, s.failed());
        assertEquals(1, s.killed());
        assertEquals(1, s.rootTasks()); // only u-1 is root
    }

    @Test
    void multipleRoots() {
        List<Task> tasks = List.of(
                task("u-1", TaskType.USER, TaskStatus.RUNNING, null),
                task("u-2", TaskType.USER, TaskStatus.PENDING, null),
                task("a-1", TaskType.AGENT, TaskStatus.PENDING, "u-1")
        );
        TaskStats s = TaskStats.from(tasks);
        assertEquals(2, s.rootTasks());
    }

    @Test
    void summary_includesAllPresentStatuses() {
        List<Task> tasks = List.of(
                task("u-1", TaskType.USER, TaskStatus.RUNNING, null),
                task("a-1", TaskType.AGENT, TaskStatus.PENDING, "u-1"),
                task("a-2", TaskType.AGENT, TaskStatus.COMPLETED, "u-1"),
                task("a-3", TaskType.AGENT, TaskStatus.FAILED, "u-1"),
                task("a-4", TaskType.AGENT, TaskStatus.KILLED, "u-1")
        );
        TaskStats s = TaskStats.from(tasks);
        String summary = s.summary();
        assertTrue(summary.contains("5 tasks"));
        assertTrue(summary.contains("1 running"));
        assertTrue(summary.contains("1 pending"));
        assertTrue(summary.contains("1 done"));
        assertTrue(summary.contains("1 failed"));
        assertTrue(summary.contains("1 killed"));
    }

    @Test
    void summary_omitsZeroCounts() {
        List<Task> tasks = List.of(
                task("u-1", TaskType.USER, TaskStatus.COMPLETED, null)
        );
        TaskStats s = TaskStats.from(tasks);
        String summary = s.summary();
        assertTrue(summary.contains("1 task")); // singular
        assertTrue(summary.contains("1 done"));
        assertFalse(summary.contains("pending"));
        assertFalse(summary.contains("running"));
        assertFalse(summary.contains("failed"));
    }

    @Test
    void fromRegistry_includesCreatedTasks() {
        TaskRegistry reg = new TaskRegistry();
        reg.create(TaskType.USER, "user query", null);
        reg.create(TaskType.AGENT, "subagent", "u-1");
        reg.create(TaskType.WORKFLOW, "workflow", "u-1");
        TaskStats s = TaskStats.from(reg);
        assertEquals(3, s.total());
        assertEquals(3, s.pending());
        assertEquals(1, s.rootTasks());
    }

    // merge

    @Test
    void merge_sumsFields() {
        TaskStats a = new TaskStats(2, 1, 1, 0, 0, 0, 1, 100L);
        TaskStats b = new TaskStats(3, 0, 1, 2, 0, 0, 1, 50L);
        TaskStats m = TaskStats.merge(java.util.List.of(a, b));
        assertEquals(5, m.total());
        assertEquals(1, m.pending());
        assertEquals(2, m.running());
        assertEquals(2, m.completed());
        assertEquals(2, m.rootTasks());
        // oldestPendingAgeMs is the max of the two (100 > 50)
        assertEquals(100L, m.oldestPendingAgeMs());
    }

    @Test
    void merge_emptyOrNull_returnsEmpty() {
        assertEquals(0, TaskStats.merge(java.util.List.of()).total());
        assertEquals(0, TaskStats.merge(null).total());
    }
}
