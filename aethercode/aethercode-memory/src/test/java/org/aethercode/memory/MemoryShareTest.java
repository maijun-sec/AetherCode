package org.aethercode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * tests for {@link MemoryShare} — cross-session tagged
 * memory lookup.
 */
class MemoryShareTest {

    @Test
    void storeAndFindByTag(@TempDir Path tmp) throws IOException {
        MemoryShare s = new MemoryShare(tmp, "share-agent");
        s.storeTagged("convention-1", "use snake_case in DB columns", Set.of("db", "style"));
        s.storeTagged("convention-2", "use tabs for indent", Set.of("style"));
        s.storeTagged("trick-1", "remember to flush", Set.of("memory"));

        Map<String, String> styleHits = s.findByTag("style");
        assertEquals(2, styleHits.size());
        assertTrue(styleHits.containsKey("convention-1"));
        assertTrue(styleHits.containsKey("convention-2"));

        Map<String, String> dbHits = s.findByTag("db");
        assertEquals(1, dbHits.size());
        assertTrue(dbHits.containsKey("convention-1"));
    }

    @Test
    void allKeys_returnsValuesAndTags(@TempDir Path tmp) throws IOException {
        MemoryShare s = new MemoryShare(tmp, "x");
        s.storeTagged("a", "value-a", Set.of("t1"));
        s.storeTagged("b", "value-b", Set.of("t2", "t3"));
        assertEquals(2, s.allKeys().size());
    }

    @Test
    void findByTag_caseInsensitive(@TempDir Path tmp) throws IOException {
        MemoryShare s = new MemoryShare(tmp, "x");
        s.storeTagged("k1", "v1", Set.of("JavaScript", "Frontend"));
        assertEquals(1, s.findByTag("javascript").size());
        assertEquals(1, s.findByTag("FRONTEND").size());
    }

    @Test
    void emptyShare_returnsEmpty(@TempDir Path tmp) throws IOException {
        MemoryShare s = new MemoryShare(tmp, "empty");
        assertTrue(s.allKeys().isEmpty());
        assertTrue(s.findByTag("anything").isEmpty());
    }
}
