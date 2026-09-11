package org.aethercode.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileBackedMemoryTest {

    @Test
    void add_persistsToDisk(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        var item = mem.add("hello", "user", List.of("greeting"));
        assertNotNull(item.id());
        assertTrue(Files.exists(f));
        assertTrue(Files.size(f) > 0);

        // Reopen: data survives
        FileBackedMemory mem2 = new FileBackedMemory(f);
        assertEquals(1, mem2.size());
        assertEquals("hello", mem2.all().get(0).content());
    }

    @Test
    void add_assignsIdTimestampScope(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        var item = mem.add("content", "project", List.of("a", "b"));
        assertNotNull(item.id());
        assertEquals("project", item.scope());
        assertEquals(List.of("a", "b"), item.tags());
        assertNotNull(item.createdAt());
    }

    @Test
    void add_nullScopeDefaultsToUser(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        var item = mem.add("x", null, null);
        assertEquals("user", item.scope());
        assertTrue(item.tags().isEmpty());
    }

    @Test
    void get_returnsById(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        var item = mem.add("findme", null, null);
        Optional<FileBackedMemory.MemoryItem> found = mem.get(item.id());
        assertTrue(found.isPresent());
        assertEquals("findme", found.get().content());
    }

    @Test
    void get_returnsEmptyForUnknown(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        assertFalse(mem.get("nope").isPresent());
    }

    @Test
    void remove_returnsTrueWhenPresent(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        var item = mem.add("a", null, null);
        assertTrue(mem.remove(item.id()));
        assertEquals(0, mem.size());
        // Persist: reopen shows empty
        FileBackedMemory mem2 = new FileBackedMemory(f);
        assertEquals(0, mem2.size());
    }

    @Test
    void remove_returnsFalseWhenAbsent(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        assertFalse(mem.remove("missing"));
    }

    @Test
    void byScope_filtersCorrectly(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        mem.add("u1", "user", null);
        mem.add("p1", "project", null);
        mem.add("u2", "user", null);
        assertEquals(2, mem.byScope("user").size());
        assertEquals(1, mem.byScope("project").size());
    }

    @Test
    void search_matchesContentAndTagsCaseInsensitive(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        mem.add("Buy milk", "user", List.of("shopping"));
        mem.add("Fix bug", "user", List.of("work"));
        mem.add("Random note", "user", null);

        List<FileBackedMemory.MemoryItem> byContent = mem.search("MILK");
        assertEquals(1, byContent.size());

        List<FileBackedMemory.MemoryItem> byTag = mem.search("work");
        assertEquals(1, byTag.size());
        assertEquals("Fix bug", byTag.get(0).content());
    }

    @Test
    void search_blankQueryReturnsAll(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        mem.add("a", null, null);
        mem.add("b", null, null);
        assertEquals(2, mem.search("").size());
        assertEquals(2, mem.search(null).size());
    }

    @Test
    void update_modifiesContentAndTags(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        var item = mem.add("original", null, List.of("a"));
        boolean ok = mem.update(item.id(), "updated", List.of("b", "c"));
        assertTrue(ok);
        var found = mem.get(item.id()).orElseThrow();
        assertEquals("updated", found.content());
        assertEquals(List.of("b", "c"), found.tags());
    }

    @Test
    void update_preservesUnspecifiedFields(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        var item = mem.add("content", "scope", null);
        mem.update(item.id(), "new-content", null);
        var found = mem.get(item.id()).orElseThrow();
        assertEquals("new-content", found.content());
        assertEquals("scope", found.scope());
    }

    @Test
    void update_returnsFalseForUnknown(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        assertFalse(mem.update("missing", "x", null));
    }

    @Test
    void clear_emptiesStore(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        mem.add("a", null, null);
        mem.add("b", null, null);
        mem.clear();
        assertEquals(0, mem.size());
    }

    @Test
    void load_corruptFileBacksUpAndStartsEmpty(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("mem.json");
        Files.writeString(f, "this is not json {");
        FileBackedMemory mem = new FileBackedMemory(f);
        assertEquals(0, mem.size());
        // .bak was created
        assertTrue(Files.exists(f.resolveSibling("mem.json.bak")));
    }

    @Test
    void load_missingFileIsEmpty(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        assertEquals(0, mem.size());
    }

    @Test
    void stats_groupsByScope(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        mem.add("a", "user", null);
        mem.add("b", "user", null);
        mem.add("c", "project", null);
        var stats = mem.stats();
        assertEquals(3, stats.get("count"));
        @SuppressWarnings("unchecked")
        var byScope = (java.util.Map<String, Integer>) stats.get("byScope");
        assertEquals(2, byScope.get("user"));
        assertEquals(1, byScope.get("project"));
    }

    @Test
    void all_returnsImmutableSnapshot(@TempDir Path tmp) {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        mem.add("a", null, null);
        var list = mem.all();
        assertEquals(1, list.size());
        assertThrows(UnsupportedOperationException.class, () -> list.add(mem.add("b", null, null)));
    }

    @Test
    void threadSafety_concurrentAdds(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("mem.json");
        FileBackedMemory mem = new FileBackedMemory(f);
        int threads = 8;
        int per = 50;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try { start.await(); for (int j = 0; j < per; j++) mem.add("x", null, null); }
                catch (InterruptedException ignored) {} finally { done.countDown(); }
            }).start();
        }
        start.countDown();
        done.await();
        assertEquals(threads * per, mem.size());
    }
}
