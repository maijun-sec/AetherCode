package org.aethercode.deepagents.selfimprove;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFileBankStorageTest {

    @Test
    void saveAndLoadAllRoundTrips(@TempDir Path dir) {
        JsonFileBankStorage s = new JsonFileBankStorage(dir);
        ReasoningUnit u = ReasoningUnit.of("k1", "err", "fix", "ex");
        s.save(u);
        List<ReasoningUnit> all = s.loadAll();
        assertEquals(1, all.size());
        assertEquals(u.id(), all.get(0).id());
        assertEquals("k1", all.get(0).taskKind());
        assertEquals("err", all.get(0).errorPattern());
        assertEquals("fix", all.get(0).fixStrategy());
        assertEquals("ex", all.get(0).example());
    }

    @Test
    void saveOverwritesExistingUnitWithSameId(@TempDir Path dir) {
        JsonFileBankStorage s = new JsonFileBankStorage(dir);
        ReasoningUnit u1 = ReasoningUnit.of("k1", "err", "fix", "ex");
        s.save(u1);
        ReasoningUnit u2 = u1.withUtility(0.9).withUses(5L);
        s.save(u2);
        List<ReasoningUnit> all = s.loadAll();
        assertEquals(1, all.size());
        assertEquals(0.9, all.get(0).utility(), 1e-9);
        assertEquals(5L, all.get(0).uses());
    }

    @Test
    void removeDeletesFromStorage(@TempDir Path dir) {
        JsonFileBankStorage s = new JsonFileBankStorage(dir);
        ReasoningUnit u = ReasoningUnit.of("k1", "err", "fix", "ex");
        s.save(u);
        assertTrue(Files.exists(dir.resolve(u.id() + ".json")));
        s.remove(u.id());
        assertFalse(Files.exists(dir.resolve(u.id() + ".json")));
        assertEquals(0, s.loadAll().size());
    }

    @Test
    void removeOfUnknownIdIsNoOp(@TempDir Path dir) {
        JsonFileBankStorage s = new JsonFileBankStorage(dir);
        s.remove("does-not-exist"); // must not throw
        assertEquals(0, s.loadAll().size());
    }

    @Test
    void loadAllOnEmptyDirIsEmpty(@TempDir Path dir) {
        JsonFileBankStorage s = new JsonFileBankStorage(dir);
        assertEquals(0, s.loadAll().size());
    }

    @Test
    void loadAllSkipsMalformedJson(@TempDir Path dir) throws IOException {
        // Drop a malformed file alongside a valid one. The valid one
        // should still load; the malformed should be skipped with a
        // warning, not crash the loader.
        JsonFileBankStorage s = new JsonFileBankStorage(dir);
        ReasoningUnit u = ReasoningUnit.of("k1", "err", "fix", "ex");
        s.save(u);
        Files.writeString(dir.resolve("garbage.json"), "{ not really json");
        List<ReasoningUnit> all = s.loadAll();
        assertEquals(1, all.size());
        assertEquals(u.id(), all.get(0).id());
    }

    @Test
    void loadAllSkipsTempFiles(@TempDir Path dir) throws IOException {
        // A stale .tmp sibling from a previous crashed write should
        // be ignored.
        Files.writeString(dir.resolve("leftover.json.tmp"), "{\"id\":\"x\"}");
        JsonFileBankStorage s = new JsonFileBankStorage(dir);
        assertEquals(0, s.loadAll().size());
    }

    @Test
    void closeIsIdempotent(@TempDir Path dir) {
        JsonFileBankStorage s = new JsonFileBankStorage(dir);
        s.close();
        s.close(); // must not throw
    }

    @Test
    void nullDirRejected() {
        try {
            new JsonFileBankStorage(null);
            assert false : "expected IllegalArgumentException";
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    void saveNullUnitIsNoOp(@TempDir Path dir) {
        JsonFileBankStorage s = new JsonFileBankStorage(dir);
        s.save(null);
        assertEquals(0, s.loadAll().size());
    }

    @Test
    void storageCreatesDirIfMissing(@TempDir Path parent) throws IOException {
        Path nested = parent.resolve("a/b/c");
        assertFalse(Files.exists(nested));
        new JsonFileBankStorage(nested);
        assertTrue(Files.isDirectory(nested));
    }
}
