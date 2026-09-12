package org.aethercode.memory;

import org.aethercode.memory.DynamicLinker.Note;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DynamicLinkerTest {

    @Test
    void newNoteAutoGeneratesId() {
        Note n = new Note(null, "x", List.of(), List.of(), null);
        assertNotNull(n.id());
        assertFalse(n.id().isBlank());
    }

    @Test
    void jaccardSimilarityBasic() {
        Note a = new Note(null, "x", List.of("java", "memory"), List.of(), null);
        Note b = new Note(null, "y", List.of("java", "memory", "ai"), List.of(), null);
        // intersection = {java, memory} = 2, union = {java, memory, ai} = 3 → 0.667
        double sim = DynamicLinker.JACCARD_KEYWORDS.apply(a, b);
        assertEquals(2.0 / 3.0, sim, 0.001);
    }

    @Test
    void addAndLinkEstablishesTwoWayLinks() {
        DynamicLinker linker = new DynamicLinker();
        List<Note> store = new ArrayList<>();
        // Pre-populate
        store.add(new Note("a", "doc a", List.of("java", "memory"), List.of(), null));
        store.add(new Note("b", "doc b", List.of("python", "ai"), List.of(), null));

        Note newNote = new Note(null, "new", List.of("java", "ai"), List.of(), null);
        List<Note> updatedExisting = new ArrayList<>();
        Note linked = linker.addAndLink(newNote, store, updatedExisting);

        // New note has links to existing (both share keywords)
        assertFalse(linked.existingLinks().isEmpty());
        assertTrue(linked.existingLinks().contains("a"));
        assertTrue(linked.existingLinks().contains("b"));
        // Existing notes are updated with back-links
        assertEquals(2, updatedExisting.size());
        for (Note u : updatedExisting) {
            assertTrue(u.existingLinks().contains(linked.id()));
        }
    }

    @Test
    void belowThresholdNoLink() {
        DynamicLinker linker = new DynamicLinker(DynamicLinker.JACCARD_KEYWORDS, 0.9, 5);
        List<Note> store = new ArrayList<>();
        store.add(new Note("a", "doc a", List.of("java"), List.of(), null));
        store.add(new Note("b", "doc b", List.of("python"), List.of(), null));

        Note newNote = new Note(null, "new", List.of("ai"), List.of(), null);
        List<Note> updatedExisting = new ArrayList<>();
        Note linked = linker.addAndLink(newNote, store, updatedExisting);
        assertTrue(linked.existingLinks().isEmpty());
        assertTrue(updatedExisting.isEmpty());
    }

    @Test
    void maxLinksPerNoteEnforced() {
        DynamicLinker linker = new DynamicLinker(DynamicLinker.JACCARD_KEYWORDS, 0.0, 2);
        List<Note> store = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            store.add(new Note("n" + i, "x" + i, List.of("common"), List.of(), null));
        }
        Note newNote = new Note(null, "new", List.of("common"), List.of(), null);
        List<Note> updated = new ArrayList<>();
        Note linked = linker.addAndLink(newNote, store, updated);
        assertTrue(linked.existingLinks().size() <= 2);
    }

    @Test
    void selfLinkIsNotCreated() {
        DynamicLinker linker = new DynamicLinker();
        List<Note> store = new ArrayList<>();
        store.add(new Note("a", "x", List.of("common"), List.of(), null));
        Note newNote = new Note("a", "x", List.of("common"), List.of(), null);
        List<Note> updated = new ArrayList<>();
        // Same id → skip
        Note linked = linker.addAndLink(newNote, store, updated);
        // No self-link
        assertFalse(linked.existingLinks().contains("a"));
    }

    @Test
    void evolveDescriptionAppendsKeyword() {
        DynamicLinker linker = new DynamicLinker();
        String evolved = linker.evolveDescription("original", new Note(null, "x",
            List.of("java", "memory", "ai"), List.of(), null));
        assertTrue(evolved.startsWith("original"));
        assertTrue(evolved.contains("java"));
    }

    @Test
    void evolveDescriptionEmptyKeywords() {
        DynamicLinker linker = new DynamicLinker();
        String evolved = linker.evolveDescription("x", new Note(null, "y", List.of(), List.of(), null));
        assertTrue(evolved.contains("(related to:"));
    }

    @Test
    void noteConstructorDefaults() {
        Note n = new Note(null, "x", null, null, null);
        assertTrue(n.keywords().isEmpty());
        assertTrue(n.tags().isEmpty());
        assertTrue(n.existingLinks().isEmpty());
    }

    @Test
    void linkRecordRejectsSelfLink() {
        assertThrows(IllegalArgumentException.class,
            () -> new DynamicLinker.Link("a", "a", 0.5));
    }

    @Test
    void linkRecordAcceptsTwoWay() {
        DynamicLinker.Link l1 = new DynamicLinker.Link("a", "b", 0.5);
        DynamicLinker.Link l2 = new DynamicLinker.Link("b", "a", 0.5);
        assertNotEquals(l1, l2);
        assertEquals(0.5, l1.similarity());
    }

    @Test
    void noteIdsHelper() {
        List<Note> store = List.of(
            new Note("a", "x", List.of(), List.of(), null),
            new Note("b", "y", List.of(), List.of(), null)
        );
        List<String> ids = DynamicLinker.noteIds(store);
        assertEquals(2, ids.size());
        assertTrue(ids.contains("a"));
        assertTrue(ids.contains("b"));
    }
}
