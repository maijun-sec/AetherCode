package org.aethercode.tasks;

import org.aethercode.core.app.AppState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TaskRegistry} and {@link TaskContext}. Verifies the
 * per-task ID semantics (prior round): tasks form a tree via parentTaskId, listeners
 * fire on every status change, terminal states are sticky.
 */
class TaskRegistryTest {

    @BeforeEach
    void reset() { TaskRegistry.resetForTests(); }
    @AfterEach
    void cleanup() { TaskRegistry.resetForTests(); }

    @Test
    void create_userTask_hasNoParentAndUsesUserPrefix() {
        TaskRegistry reg = TaskRegistry.instance();
        Task t = reg.create(TaskType.USER, "summarize the README", null);
        assertNotNull(t.id());
        assertTrue(t.id().startsWith("u-"), "user task id should start with u-, got " + t.id());
        assertEquals(TaskType.USER, t.type());
        assertEquals(TaskStatus.PENDING, t.status());
        assertTrue(t.isRoot());
        assertTrue(t.parentTaskId() == null);
        assertTrue(t.createdAtMs() > 0);
    }

    @Test
    void create_subagent_hasParentAndAgentPrefix() {
        TaskRegistry reg = TaskRegistry.instance();
        Task parent = reg.create(TaskType.USER, "do work", null);
        Task child = reg.create(TaskType.AGENT, "sub-task", parent.id());
        assertTrue(child.id().startsWith("a-"));
        assertEquals(parent.id(), child.parentTaskId());
        assertFalse(child.isRoot());
        assertEquals(1, reg.listChildren(parent.id()).size());
    }

    @Test
    void updateStatus_transitionsToRunningAndCompleted() {
        TaskRegistry reg = TaskRegistry.instance();
        Task t = reg.create(TaskType.USER, "x", null);
        reg.updateStatus(t.id(), TaskStatus.RUNNING);
        assertEquals(TaskStatus.RUNNING, reg.get(t.id()).orElseThrow().status());
        reg.updateStatus(t.id(), TaskStatus.COMPLETED);
        Task ended = reg.get(t.id()).orElseThrow();
        assertEquals(TaskStatus.COMPLETED, ended.status());
        assertTrue(ended.endedAtMs() >= ended.createdAtMs(),
                "endedAtMs should be set on completion");
        assertTrue(ended.status().isTerminal());
    }

    @Test
    void updateStatus_terminalIsSticky() {
        TaskRegistry reg = TaskRegistry.instance();
        Task t = reg.create(TaskType.USER, "x", null);
        reg.updateStatus(t.id(), TaskStatus.COMPLETED);
        // Completed is terminal — further updates are no-ops.
        reg.updateStatus(t.id(), TaskStatus.RUNNING);
        assertEquals(TaskStatus.COMPLETED, reg.get(t.id()).orElseThrow().status());
    }

    @Test
    void listener_firesOnEveryChange() {
        TaskRegistry reg = TaskRegistry.instance();
        AtomicInteger seen = new AtomicInteger();
        reg.onChange(t -> seen.incrementAndGet());
        Task t = reg.create(TaskType.USER, "x", null);
        reg.updateStatus(t.id(), TaskStatus.RUNNING);
        reg.updateStatus(t.id(), TaskStatus.COMPLETED);
        assertEquals(3, seen.get(), "listener should fire on create + 2 status updates");
    }

    @Test
    void listener_throwsAreIsolated() {
        TaskRegistry reg = TaskRegistry.instance();
        reg.onChange(t -> { throw new RuntimeException("boom"); });
        // Must not abort the registry.
        Task t = reg.create(TaskType.USER, "x", null);
        assertNotNull(reg.get(t.id()).orElse(null));
    }

    @Test
    void taskContext_holdsAppStateAndAbortFlag() {
        TaskRegistry reg = TaskRegistry.instance();
        AppState st = new AppState("s1", Path.of(""));
        Task t = reg.create(TaskType.USER, "x", null);
        TaskContext ctx = new TaskContext(t, st, Path.of(System.getProperty("java.io.tmpdir")),
                reg, null);
        assertFalse(ctx.isAborted());
        ctx.abort();
        assertTrue(ctx.isAborted());
        assertSame(st, ctx.appState());
        assertEquals(t.id(), ctx.task().id());
    }

    @Test
    void taskContext_childrenListsRegistryChildren() {
        TaskRegistry reg = TaskRegistry.instance();
        AppState st = new AppState("s1", Path.of(""));
        Task parent = reg.create(TaskType.USER, "main", null);
        Task a = reg.create(TaskType.AGENT, "sub-a", parent.id());
        Task b = reg.create(TaskType.AGENT, "sub-b", parent.id());
        TaskContext ctx = new TaskContext(parent, st, Path.of("."), reg, null);
        var children = ctx.children();
        assertEquals(2, children.size());
        assertTrue(children.contains(a));
        assertTrue(children.contains(b));
    }

    @Test
    void idsAreUniqueAcrossManyTasks() {
        TaskRegistry reg = TaskRegistry.instance();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            String id = reg.create(TaskType.USER, "t" + i, null).id();
            assertTrue(ids.add(id), "id collision: " + id);
        }
        assertEquals(200, reg.list().size());
    }
}
