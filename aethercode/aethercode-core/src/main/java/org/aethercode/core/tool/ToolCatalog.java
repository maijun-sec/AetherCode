package org.aethercode.core.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * a registry of {@link Tool}s. Unlike {@link ToolHookRegistry}
 * (prior round) which is about the per-call lifecycle, the catalog answers
 * "which tools are available, what do they look like, how do I find
 * one by name or by capability predicate?".
 */
public class ToolCatalog {

    public record Entry(Tool tool, String category, boolean deprecated) {
        public Entry(Tool tool) { this(tool, "general", false); }
        public Entry(Tool tool, String category) { this(tool, category, false); }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final List<Predicate<Tool>> filters = new CopyOnWriteArrayList<>();

    public ToolCatalog register(Tool tool) {
        return register(tool, "general", false);
    }

    public ToolCatalog register(Tool tool, String category) {
        return register(tool, category, false);
    }

    public ToolCatalog register(Tool tool, String category, boolean deprecated) {
        Objects.requireNonNull(tool, "tool");
        if (tool.name() == null || tool.name().isBlank()) throw new IllegalArgumentException("tool name required");
        entries.put(tool.name(), new Entry(tool, category, deprecated));
        return this;
    }

    public ToolCatalog unregister(String name) {
        entries.remove(name);
        return this;
    }

    public Optional<Tool> get(String name) {
        if (name == null) return Optional.empty();
        Entry e = entries.get(name);
        return e == null ? Optional.empty() : Optional.of(e.tool());
    }

    public Optional<Entry> getEntry(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(entries.get(name));
    }

    public boolean contains(String name) {
        return name != null && entries.containsKey(name);
    }

    public List<Tool> all() {
        List<Tool> out = new ArrayList<>();
        for (Entry e : entries.values()) out.add(e.tool());
        return out;
    }

    public List<Entry> allEntries() { return List.copyOf(entries.values()); }

    public List<Tool> byCategory(String category) {
        List<Tool> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (e.category().equals(category)) out.add(e.tool());
        }
        return out;
    }

    public List<String> categories() {
        List<String> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (!out.contains(e.category())) out.add(e.category());
        }
        return out;
    }

    public ToolCatalog addFilter(Predicate<Tool> filter) {
        if (filter != null) filters.add(filter);
        return this;
    }

    public List<Tool> applyFilters() {
        if (filters.isEmpty()) return all();
        List<Tool> out = new ArrayList<>();
        for (Entry e : entries.values()) {
            boolean ok = true;
            for (Predicate<Tool> f : filters) {
                if (!f.test(e.tool())) { ok = false; break; }
            }
            if (ok) out.add(e.tool());
        }
        return out;
    }

    public int size() { return entries.size(); }

    public void clear() { entries.clear(); }

    /** register all from a collection. */
    public ToolCatalog registerAll(Collection<Tool> tools) {
        for (Tool t : tools) register(t);
        return this;
    }

    /** get a tool by its name, with a typed cast. */
    @SuppressWarnings("unchecked")
    public <T extends Tool> Optional<T> getAs(String name, Class<T> type) {
        if (name == null || type == null) return Optional.empty();
        Entry e = entries.get(name);
        if (e == null) return Optional.empty();
        if (!type.isInstance(e.tool())) return Optional.empty();
        return Optional.of((T) e.tool());
    }
}
