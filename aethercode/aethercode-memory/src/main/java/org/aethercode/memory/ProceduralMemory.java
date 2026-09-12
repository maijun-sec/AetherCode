package org.aethercode.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Paper 2606.06787 AdMem: Procedural memory.
 * <p>
 * Procedural memory stores reusable procedures / skill descriptions
 * (e.g. "how to call API X", "how to recover from error Y"). It is
 * distinct from semantic memory (facts) and episodic memory (past
 * experiences).
 */
public final class ProceduralMemory {

    /** A single procedural memory record. */
    public record Procedure(
        String id,
        String name,
        String description,
        List<String> steps,
        Map<String, Object> parameters,
        long createdAtMs
    ) {
        public Procedure {
            if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(description, "description");
            steps = steps != null ? List.copyOf(steps) : List.of();
            parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        }
    }

    private final Map<String, Procedure> store = new LinkedHashMap<>();

    public Procedure add(Procedure p) {
        store.put(p.id(), p);
        return p;
    }

    public Procedure add(String name, String description, List<String> steps) {
        return add(new Procedure(null, name, description, steps, Map.of(), System.currentTimeMillis()));
    }

    public Procedure get(String id) {
        return store.get(id);
    }

    public boolean remove(String id) {
        return store.remove(id) != null;
    }

    public int size() {
        return store.size();
    }

    public List<Procedure> all() {
        return List.copyOf(store.values());
    }

    /** Find procedures by simple substring match on name + description. */
    public List<Procedure> findByKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        String k = keyword.toLowerCase();
        return store.values().stream()
            .filter(p -> p.name().toLowerCase().contains(k) || p.description().toLowerCase().contains(k))
            .toList();
    }
}
