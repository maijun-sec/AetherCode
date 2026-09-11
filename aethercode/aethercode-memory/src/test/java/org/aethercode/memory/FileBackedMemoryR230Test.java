package org.aethercode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FileBackedMemoryR230Test {

    @Test
    void addWithSensitivityStoresLabel(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        var item = mem.add("ssh private key path", "user", List.of(), Sensitivity.PII);
        assertEquals(Sensitivity.PII, item.sensitivity());
        var found = mem.get(item.id()).orElseThrow();
        assertEquals(Sensitivity.PII, found.sensitivity());
    }

    @Test
    void addDefaultsToInternal(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        var item = mem.add("normal note", "user", null);
        assertEquals(Sensitivity.INTERNAL, item.sensitivity());
    }

    @Test
    void touchBumpsAccessCount(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        var item = mem.add("hot", "user", null);
        assertEquals(0L, item.accessCount());
        var t1 = mem.touch(item.id()).orElseThrow();
        assertEquals(1L, t1.accessCount());
        var t2 = mem.touch(item.id()).orElseThrow();
        assertEquals(2L, t2.accessCount());
        var t3 = mem.touch(item.id()).orElseThrow();
        assertEquals(3L, t3.accessCount());
    }

    @Test
    void touchOnUnknownReturnsEmpty(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        assertTrue(mem.touch("nope").isEmpty());
    }

    @Test
    void setSensitivityUpdatesLabel(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        var item = mem.add("normal", "user", null);
        assertEquals(Sensitivity.INTERNAL, item.sensitivity());
        var updated = mem.setSensitivity(item.id(), Sensitivity.SENSITIVE).orElseThrow();
        assertEquals(Sensitivity.SENSITIVE, updated.sensitivity());
        var reloaded = mem.get(item.id()).orElseThrow();
        assertEquals(Sensitivity.SENSITIVE, reloaded.sensitivity());
    }

    @Test
    void setSensitivityOnUnknownReturnsEmpty(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        assertTrue(mem.setSensitivity("nope", Sensitivity.PII).isEmpty());
    }

    @Test
    void setSensitivityNullReturnsEmpty(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        var item = mem.add("x", "user", null);
        assertTrue(mem.setSensitivity(item.id(), null).isEmpty());
    }

    @Test
    void sensitivitySurvivesReopen(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        var item = mem.add("ssh", "user", null, Sensitivity.PII);
        FileBackedMemory mem2 = new FileBackedMemory(f);
        var reloaded = mem2.get(item.id()).orElseThrow();
        assertEquals(Sensitivity.PII, reloaded.sensitivity());
        assertEquals(0L, reloaded.accessCount());
    }

    @Test
    void touchPersistsToDisk(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        var item = mem.add("hot", "user", null);
        mem.touch(item.id());
        mem.touch(item.id());
        FileBackedMemory mem2 = new FileBackedMemory(f);
        var reloaded = mem2.get(item.id()).orElseThrow();
        assertEquals(2L, reloaded.accessCount());
    }

    @Test
    void existingAddStillWorksWithOldShape(@TempDir Path tmp) {
        // Backward compatibility: 3-arg add() is the existing public API
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        var item = mem.add("legacy", "user", List.of("a"));
        assertEquals("legacy", item.content());
        assertEquals(Sensitivity.INTERNAL, item.sensitivity());
        assertEquals(0L, item.accessCount());
    }
}
