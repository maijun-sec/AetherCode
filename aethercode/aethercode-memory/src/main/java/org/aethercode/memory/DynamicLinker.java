package org.aethercode.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * A-Mem-style dynamic linker.
 * <p>
 * Paper: 2502.12110 A-Mem (Zettelkasten memory). When a new note is added to the
 * store, the linker scans existing notes and finds the most semantically similar
 * ones. Two-way links are established automatically.
 * <p>
 * The similarity function is pluggable; the default is Jaccard similarity over
 * keyword sets.
 * <p>
 * The linker is intentionally deterministic (no LLM at runtime) — production
 * code can swap in a real embedding-based similarity.
 */
public final class DynamicLinker {

    /** A single link between two notes. */
    public record Link(String fromId, String toId, double similarity) {
        public Link {
            Objects.requireNonNull(fromId, "fromId");
            Objects.requireNonNull(toId, "toId");
            if (fromId.equals(toId)) {
                throw new IllegalArgumentException("self-link not allowed");
            }
        }
    }

    /** A note with 5 attributes (A-Mem §3). */
    public record Note(
        String id,
        String contextualDescription,
        List<String> keywords,
        List<String> tags,
        List<String> existingLinks
    ) {
        public Note {
            if (id == null || id.isBlank()) {
                id = UUID.randomUUID().toString();
            }
            keywords = keywords != null ? List.copyOf(keywords) : List.of();
            tags = tags != null ? List.copyOf(tags) : List.of();
            existingLinks = existingLinks != null ? List.copyOf(existingLinks) : List.of();
            Objects.requireNonNull(contextualDescription, "contextualDescription");
        }
    }

    /** Default similarity: Jaccard over keyword sets. */
    public static final BiFunction<Note, Note, Double> JACCARD_KEYWORDS = (a, b) -> {
        if (a.keywords().isEmpty() && b.keywords().isEmpty()) return 0.0;
        Set<String> A = new HashSet<>(a.keywords());
        Set<String> B = new HashSet<>(b.keywords());
        Set<String> inter = new HashSet<>(A);
        inter.retainAll(B);
        Set<String> union = new HashSet<>(A);
        union.addAll(B);
        if (union.isEmpty()) return 0.0;
        return (double) inter.size() / union.size();
    };

    private final BiFunction<Note, Note, Double> similarityFn;
    private final double linkThreshold;
    private final int maxLinksPerNote;

    public DynamicLinker() {
        this(JACCARD_KEYWORDS, 0.2, 5);
    }

    public DynamicLinker(BiFunction<Note, Note, Double> similarityFn, double linkThreshold, int maxLinksPerNote) {
        this.similarityFn = Objects.requireNonNull(similarityFn, "similarityFn");
        this.linkThreshold = linkThreshold;
        this.maxLinksPerNote = maxLinksPerNote;
    }

    /**
     * Add a new note to the store. Returns the new note (with auto-generated id
     * and updated links). All other notes in {@code existing} may also have
     * their links updated (returned in {@code updatedExisting}).
     *
     * @param newNote      the new note (id may be null → auto-generated)
     * @param existing     the current store
     * @param updatedExisting output list of updated existing notes (their link list grew)
     * @return the new note with links set
     */
    public Note addAndLink(Note newNote, List<Note> existing, List<Note> updatedExisting) {
        Objects.requireNonNull(newNote, "newNote");
        Objects.requireNonNull(existing, "existing");
        Objects.requireNonNull(updatedExisting, "updatedExisting");

        // 1. Find top-K similar existing notes
        List<Scored> scored = new ArrayList<>();
        for (Note n : existing) {
            if (n.id().equals(newNote.id())) continue;
            double sim = similarityFn.apply(newNote, n);
            if (sim >= linkThreshold) {
                scored.add(new Scored(n, sim));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        // 2. Build the link set for the new note
        List<String> newLinks = new ArrayList<>();
        for (int i = 0; i < scored.size() && newLinks.size() < maxLinksPerNote; i++) {
            newLinks.add(scored.get(i).note.id());
        }

        Note linked = new Note(newNote.id(), newNote.contextualDescription(),
            newNote.keywords(), newNote.tags(), newLinks);

        // 3. Update existing notes (two-way links)
        for (Scored s : scored.subList(0, Math.min(scored.size(), maxLinksPerNote))) {
            if (!s.note.existingLinks().contains(newNote.id())) {
                List<String> updated = new ArrayList<>(s.note.existingLinks());
                updated.add(newNote.id());
                Note updatedNote = new Note(s.note.id(), s.note.contextualDescription(),
                    s.note.keywords(), s.note.tags(), updated);
                updatedExisting.add(updatedNote);
            }
        }

        return linked;
    }

    /**
     * Evolve: when a new note is added, update the contextual description of
     * existing notes that linked to it (A-Mem §3.3 "memory evolution").
     * <p>
     * The default evolution strategy appends " (related to: &lt;new keyword&gt;)" to
     * the existing description; the callable is pluggable.
     */
    public String evolveDescription(String existingDescription, Note newNote) {
        if (newNote.keywords().isEmpty()) {
            return existingDescription + " (related to: " + newNote.id() + ")";
        }
        return existingDescription + " (related to: " + String.join(", ", newNote.keywords().subList(0, Math.min(2, newNote.keywords().size()))) + ")";
    }

    /** All notes in the store, as an unmodifiable list. */
    public static List<String> noteIds(List<Note> notes) {
        List<String> ids = new ArrayList<>();
        for (Note n : notes) ids.add(n.id());
        return Collections.unmodifiableList(ids);
    }

    private record Scored(Note note, double score) {}
}
