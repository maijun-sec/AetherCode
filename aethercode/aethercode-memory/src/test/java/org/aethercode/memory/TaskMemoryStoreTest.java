package org.aethercode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * per-task memory isolation. Two tasks must never see each
 * other's memories, even when they share the same agent type.
 */
class TaskMemoryStoreTest {

    @Test
    void storeAndRecallPerTask(@TempDir Path tmp) throws IOException {
        TaskMemoryStore store = new TaskMemoryStore(tmp, "test-agent");
        store.store("task-A", "note", "alpha payload");
        store.store("task-B", "note", "beta payload");

        List<TaskMemoryStore.Entry> aNotes = store.recall("task-A", "", 10);
        List<TaskMemoryStore.Entry> bNotes = store.recall("task-B", "", 10);

        assertEquals(1, aNotes.size());
        assertEquals(1, bNotes.size());
        assertEquals("alpha payload", aNotes.get(0).value());
        assertEquals("beta payload", bNotes.get(0).value());
    }

    @Test
    void tasksAreIsolated_recallDoesNotLeakAcrossTasks(@TempDir Path tmp) throws IOException {
        TaskMemoryStore store = new TaskMemoryStore(tmp, "shared-agent");
        // Task A writes a sensitive note. Task B must NOT see it.
        store.store("task-A", "secret", "the launch codes");
        store.store("task-B", "public", "the marketing copy");

        List<TaskMemoryStore.Entry> bList = store.list("task-B");
        assertEquals(1, bList.size());
        assertEquals("public", bList.get(0).key());
        assertFalse(bList.stream().anyMatch(e -> "secret".equals(e.key())),
                "task-B must not see task-A's 'secret' key");
    }

    @Test
    void taskView_scopesAllOperations(@TempDir Path tmp) throws IOException {
        TaskMemoryStore store = new TaskMemoryStore(tmp, "view-agent");
        TaskMemoryStore.TaskView viewA = store.forTask("task-A");
        TaskMemoryStore.TaskView viewB = store.forTask("task-B");

        viewA.store("from-a", "hello from A");
        viewB.store("from-b", "hello from B");

        List<TaskMemoryStore.Entry> aOnly = viewA.list();
        List<TaskMemoryStore.Entry> bOnly = viewB.list();

        assertEquals(1, aOnly.size());
        assertEquals("from-a", aOnly.get(0).key());
        assertEquals(1, bOnly.size());
        assertEquals("from-b", bOnly.get(0).key());
    }

    @Test
    void taskViewRecallFiltersByQuery(@TempDir Path tmp) throws IOException {
        TaskMemoryStore store = new TaskMemoryStore(tmp, "search-agent");
        TaskMemoryStore.TaskView view = store.forTask("task-X");
        view.store("user_pref", "dark mode");
        view.store("user_name", "alice");
        view.store("system", "linux");

        List<TaskMemoryStore.Entry> userHits = view.recall("user", 10);
        assertEquals(2, userHits.size());
        assertTrue(userHits.stream().allMatch(e -> e.key().startsWith("user_")));
    }

    @Test
    void deleteKeyRemovesOnlyThatKey(@TempDir Path tmp) throws IOException {
        TaskMemoryStore store = new TaskMemoryStore(tmp, "del-agent");
        TaskMemoryStore.TaskView view = store.forTask("task-1");
        view.store("k1", "v1");
        view.store("k2", "v2");
        view.store("k3", "v3");

        assertTrue(view.delete("k2"));
        List<TaskMemoryStore.Entry> after = view.list();
        assertEquals(2, after.size());
        assertTrue(after.stream().noneMatch(e -> "k2".equals(e.key())));
    }

    @Test
    void clearTaskRemovesEverythingForThatTask(@TempDir Path tmp) throws IOException {
        TaskMemoryStore store = new TaskMemoryStore(tmp, "clear-agent");
        store.store("task-1", "a", "x");
        store.store("task-1", "b", "y");
        store.store("task-2", "c", "z");

        assertTrue(store.clearTask("task-1"));
        assertEquals(0, store.list("task-1").size());
        // task-2 should be unaffected.
        assertEquals(1, store.list("task-2").size());
    }

    @Test
    void taskIds_listsAllTasksWithMemories(@TempDir Path tmp) throws IOException {
        TaskMemoryStore store = new TaskMemoryStore(tmp, "ids-agent");
        store.store("alpha", "k", "v");
        store.store("beta", "k", "v");
        store.store("gamma", "k", "v");

        List<String> ids = store.taskIds();
        assertEquals(List.of("alpha", "beta", "gamma"), ids);
    }

    @Test
    void differentAgentTypesAreAlsoIsolated(@TempDir Path tmp) throws IOException {
        // Same task id, but different agent types — still isolated
        // because the path includes the agent type.
        TaskMemoryStore storeA = new TaskMemoryStore(tmp, "agent-A");
        TaskMemoryStore storeB = new TaskMemoryStore(tmp, "agent-B");
        storeA.store("task-1", "note", "from agent A");
        storeB.store("task-1", "note", "from agent B");

        assertEquals("from agent A", storeA.recall("task-1", "", 1).get(0).value());
        assertEquals("from agent B", storeB.recall("task-1", "", 1).get(0).value());
    }

    @Test
    void valueSizeLimitEnforced(@TempDir Path tmp) {
        TaskMemoryStore store = new TaskMemoryStore(tmp, "size-agent");
        // 65KB exceeds 64KB limit.
        String huge = "x".repeat(65 * 1024);
        assertThrows(IllegalArgumentException.class, () ->
                store.store("task-1", "huge", huge));
    }

    @Test
    void keySanitisation_unsafeCharsReplaced(@TempDir Path tmp) throws IOException {
        TaskMemoryStore store = new TaskMemoryStore(tmp, "key-agent");
        store.store("task-1", "key/with/slashes", "value");
        // The key "key/with/slashes" is sanitised to "key_with_slashes"
        // — which would be a single file, not subdirectories.
        assertEquals(1, store.list("task-1").size());
        assertEquals("key_with_slashes", store.list("task-1").get(0).key());
    }

    @Test
    void recall_emptyTaskReturnsEmpty(@TempDir Path tmp) throws IOException {
        TaskMemoryStore store = new TaskMemoryStore(tmp, "empty-agent");
        assertTrue(store.list("nonexistent").isEmpty());
    }

    @Test
    void maxAgeMs_zeroMeansNoExpiry(@TempDir Path tmp) throws Exception {
        TaskMemoryStore store = new TaskMemoryStore(tmp, "no-expiry", 0L);
        store.store("task-1", "k", "v");
        // maxAgeMs=0 → never expires.
        Thread.sleep(50);
        assertEquals(1, store.list("task-1").size());
    }

    @Test
    void maxAgeMs_skipsExpiredOnRecall(@TempDir Path tmp) throws Exception {
        TaskMemoryStore store = new TaskMemoryStore(tmp, "expiring", 50L);
        store.store("task-1", "k", "v");
        Thread.sleep(100);
        // Entry is older than 50ms, so it's skipped.
        assertTrue(store.list("task-1").isEmpty());
    }

    @Test
    void constructor_rejectsBadArgs(@TempDir Path tmp) {
        assertThrows(IllegalArgumentException.class, () -> new TaskMemoryStore(null, "x"));
        assertThrows(IllegalArgumentException.class, () -> new TaskMemoryStore(tmp, null));
        assertThrows(IllegalArgumentException.class, () -> new TaskMemoryStore(tmp, ""));
        assertThrows(IllegalArgumentException.class, () -> new TaskMemoryStore(tmp, "x", -1));
    }

    @Test
    void taskView_rejectsBlankTaskId(@TempDir Path tmp) {
        TaskMemoryStore store = new TaskMemoryStore(tmp, "view-test");
        assertThrows(IllegalArgumentException.class, () -> store.forTask(null));
        assertThrows(IllegalArgumentException.class, () -> store.forTask(""));
    }

    @Test
    void filesAreOnDisk_underExpectedPath(@TempDir Path tmp) throws IOException {
        TaskMemoryStore store = new TaskMemoryStore(tmp, "disk-agent");
        store.store("task-42", "key1", "value1");
        Path expected = tmp.resolve("agent-memory-tasks")
                .resolve("disk-agent")
                .resolve("task-42")
                .resolve("key1");
        assertTrue(Files.isRegularFile(expected), "memory file should exist at " + expected);
        assertEquals("value1", Files.readString(expected));
    }

    // batch cleanup

    @Test
    void clearTasksOlderThan_removesStaleTasks(@TempDir Path tmp) throws Exception {
        TaskMemoryStore store = new TaskMemoryStore(tmp, "cleanup-agent");
        // Write old-task first, wait, then write new-task. Now
        // old-task mtime is 50ms+ ago, new-task mtime is 0ms ago.
        store.store("old-task", "k", "v");
        Thread.sleep(60);
        store.store("new-task", "k", "v");
        // Clean up tasks whose newest file is older than 30ms —
        // old-task qualifies, new-task does not.
        List<String> cleaned = store.clearTasksOlderThan(30L);
        assertEquals(List.of("old-task"), cleaned);
        assertEquals(0, store.list("old-task").size());
        assertEquals(1, store.list("new-task").size());
    }

    @Test
    void clearTasksOlderThan_zeroRemovesEverything(@TempDir Path tmp) throws IOException {
        TaskMemoryStore store = new TaskMemoryStore(tmp, "nuke-agent");
        store.store("a", "k", "v");
        store.store("b", "k", "v");
        store.store("c", "k", "v");
        List<String> cleaned = store.clearTasksOlderThan(0L);
        assertEquals(3, cleaned.size());
        assertEquals(0, store.taskIds().size());
    }

    // per-task budget

    @Test
    void perTaskBudgetEvictsOldest(@TempDir Path tmp) throws Exception {
        // 4KB budget. Each "big" value is 1.5KB. After 3 writes
        // the budget is exceeded; the oldest is evicted.
        TaskMemoryStore store = new TaskMemoryStore(tmp, "budget-agent", 0L, 4L * 1024);
        String big = "x".repeat(1_500);
        store.store("task-1", "k1", big);
        Thread.sleep(20);
        store.store("task-1", "k2", big);
        Thread.sleep(20);
        store.store("task-1", "k3", big);
        // Total now > 4KB. The third write should have triggered eviction.
        long totalAfter3 = store.taskSizeBytes("task-1");
        assertTrue(totalAfter3 <= 4L * 1024, "total should be under budget, was " + totalAfter3);
        // The oldest (k1) should be gone.
        var remaining = store.list("task-1");
        assertFalse(remaining.stream().anyMatch(e -> "k1".equals(e.key())),
                "oldest entry should be evicted");
    }

    @Test
    void taskSizeBytesReportsTotal(@TempDir Path tmp) throws Exception {
        TaskMemoryStore store = new TaskMemoryStore(tmp, "size-agent");
        store.store("t", "a", "12345");
        assertEquals(5L, store.taskSizeBytes("t"));
    }
}
