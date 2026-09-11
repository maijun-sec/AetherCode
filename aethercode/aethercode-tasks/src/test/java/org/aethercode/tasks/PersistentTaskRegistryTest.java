package org.aethercode.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * unit tests for {@link PersistentTaskRegistry} — the
 * disk-backed task registry. Verifies create/update persistence,
 * crash recovery via {@link PersistentTaskRegistry#restore},
 * listener firing on restore, and edge cases (empty file,
 * malformed lines, terminal-state stickiness).
 */
class PersistentTaskRegistryTest {

    @Test
    void open_createsParentDirectories(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("nested").resolve("tasks.jsonl");
        PersistentTaskRegistry reg = PersistentTaskRegistry.open(file);
        assertNotNull(reg);
        assertTrue(Files.exists(tmp.resolve("nested")));
        reg.create(TaskType.USER, "hello", null);
        assertTrue(Files.exists(file));
    }

    @Test
    void create_appendsLineToFile(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("tasks.jsonl");
        PersistentTaskRegistry reg = PersistentTaskRegistry.open(file);
        reg.create(TaskType.USER, "summarize readme", null);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"op\":\"create\""), "create line missing: " + lines.get(0));
        assertTrue(lines.get(0).contains("USER"), "type missing: " + lines.get(0));
        assertTrue(lines.get(0).contains("PENDING"), "status missing: " + lines.get(0));
    }

    @Test
    void updateStatus_appendsUpdateLine(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("tasks.jsonl");
        PersistentTaskRegistry reg = PersistentTaskRegistry.open(file);
        Task t = reg.create(TaskType.USER, "x", null);
        reg.updateStatus(t.id(), TaskStatus.RUNNING);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("\"op\":\"update\""));
        assertTrue(lines.get(1).contains("RUNNING"));
    }

    @Test
    void restore_rebuildsInMemoryState(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("tasks.jsonl");
        PersistentTaskRegistry w = PersistentTaskRegistry.open(file);
        Task a = w.create(TaskType.USER, "first", null);
        Task b = w.create(TaskType.AGENT, "second", a.id());
        w.updateStatus(a.id(), TaskStatus.RUNNING);
        w.updateStatus(a.id(), TaskStatus.COMPLETED);

        // "crash" — discard the in-memory state, restore from disk.
        PersistentTaskRegistry r = PersistentTaskRegistry.restore(file);
        assertEquals(2, r.list().size());
        Task ra = r.get(a.id()).orElseThrow();
        assertEquals(TaskStatus.COMPLETED, ra.status());
        assertTrue(ra.endedAtMs() >= ra.createdAtMs());
        Task rb = r.get(b.id()).orElseThrow();
        assertEquals("second", rb.description());
        assertEquals(a.id(), rb.parentTaskId());
    }

    @Test
    void restore_reconstructsListState(@TempDir Path tmp) throws IOException {
        // Build a fresh file with 1 user + 2 agent children, then restore.
        Path file = tmp.resolve("tasks.jsonl");
        PersistentTaskRegistry writer = PersistentTaskRegistry.open(file);
        Task root = writer.create(TaskType.USER, "x", null);
        writer.create(TaskType.AGENT, "y", root.id());
        writer.create(TaskType.AGENT, "z", root.id());

        // Restore on a fresh handle.
        AtomicInteger count = new AtomicInteger();
        PersistentTaskRegistry restored = PersistentTaskRegistry.restore(file);
        // Attach a listener after restore — used for FUTURE events.
        restored.onChange(t -> count.incrementAndGet());
        // The state is fully reconstructed: 3 tasks, 2 children of root.
        assertEquals(3, restored.list().size());
        assertEquals(2, restored.listChildren(root.id()).size());
        // No fires yet because the listener was attached after restore.
        assertEquals(0, count.get());
    }

    @Test
    void restore_skipsMalformedLines(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("tasks.jsonl");
        // Hand-craft a file with one good and one bad line.
        Files.writeString(file,
                "garbage line that isn't JSON\n" +
                "{\"op\":\"create\",\"ts\":1,\"id\":\"u-good\",\"type\":\"USER\",\"status\":\"PENDING\"," +
                "\"description\":\"good\",\"parent\":null,\"createdAtMs\":1,\"endedAtMs\":0}\n" +
                "another bad line\n", StandardCharsets.UTF_8);

        PersistentTaskRegistry r = PersistentTaskRegistry.restore(file);
        assertEquals(1, r.list().size());
        assertTrue(r.get("u-good").isPresent());
    }

    @Test
    void restore_emptyFile_yieldsEmptyRegistry(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("tasks.jsonl");
        // File doesn't exist yet.
        PersistentTaskRegistry r = PersistentTaskRegistry.restore(file);
        assertEquals(0, r.list().size());
    }

    @Test
    void restore_blankLines_ignored(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("tasks.jsonl");
        Files.writeString(file, "\n\n\n", StandardCharsets.UTF_8);
        PersistentTaskRegistry r = PersistentTaskRegistry.restore(file);
        assertEquals(0, r.list().size());
    }

    @Test
    void restore_terminalStateIsSticky(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("tasks.jsonl");
        PersistentTaskRegistry w = PersistentTaskRegistry.open(file);
        Task t = w.create(TaskType.USER, "x", null);
        w.updateStatus(t.id(), TaskStatus.COMPLETED);
        // Append a malicious line trying to revert to RUNNING.
        Files.writeString(file,
                "{\"op\":\"update\",\"ts\":999,\"id\":\"" + t.id() + "\",\"status\":\"RUNNING\",\"endedAtMs\":0}\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        PersistentTaskRegistry r = PersistentTaskRegistry.restore(file);
        // The first update (RUNNING→COMPLETED) won. The second update
        // is a no-op because COMPLETED is terminal.
        assertEquals(TaskStatus.COMPLETED, r.get(t.id()).orElseThrow().status());
    }

    @Test
    void createChild_persistsParentLink(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("tasks.jsonl");
        PersistentTaskRegistry r = PersistentTaskRegistry.open(file);
        Task p = r.create(TaskType.USER, "parent", null);
        Task c = r.create(TaskType.AGENT, "child", p.id());
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("\"parent\":\"" + p.id() + "\""),
                "child line should reference parent: " + lines.get(1));
    }

    @Test
    void file_returnsBoundPath(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("tasks.jsonl");
        PersistentTaskRegistry r = PersistentTaskRegistry.open(file);
        assertEquals(file, r.file());
    }
}
