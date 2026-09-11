package org.aethercode.core.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The full agent state.
 *
 * <p>Java-native port of the Python {@code AgentState} TypedDict from
 * {@code deepagents.graph}. The shape is intentionally open: a
 * {@code Map<String, Object>} for extension keys plus a few well-known
 * shortcuts ({@code messages}, {@code files}).</p>
 *
 * <p>The state is immutable; updates return a new state via
 * {@link #withMessages(List)} and {@link #withFiles(Map)}.</p>
 */
public record AgentState(
        List<Message> messages,
        Map<String, Object> files,
        Map<String, Object> extensions
) {

    public AgentState {
        messages = messages == null ? List.of() : List.copyOf(messages);
        files = files == null ? Map.of() : Map.copyOf(files);
        extensions = extensions == null ? Map.of() : Map.copyOf(extensions);
    }

    public static AgentState empty() {
        return new AgentState(List.of(), Map.of(), Map.of());
    }

    /**
     * Minimal nested map type used by the
     * {@code Object} marker. The
     * runtime does not interpret this type; it exists so the
     * FilesystemState record can carry an
     * {@code AgentStateMap} field without colliding with the
     * top-level {@code AgentState.agents} field.
     */
    public record AgentStateMap(java.util.Map<String, ?> entries) {
        public AgentStateMap {
            entries = entries == null ? java.util.Map.of() : java.util.Map.copyOf(entries);
        }
    }

    public static AgentState of(List<Message> messages) {
        return new AgentState(messages, Map.of(), Map.of());
    }

    public static AgentState of(List<Message> messages,
                                Map<String, Object> files) {
        return new AgentState(messages, files, Map.of());
    }

    /** Return a copy with the messages replaced. */
    public AgentState withMessages(List<Message> newMessages) {
        Objects.requireNonNull(newMessages, "newMessages");
        return new AgentState(newMessages, files, extensions);
    }

    /** Return a copy with the files replaced. */
    public AgentState withFiles(Map<String, Object> newFiles) {
        Objects.requireNonNull(newFiles, "newFiles");
        return new AgentState(messages, newFiles, extensions);
    }

    /** Return a copy with the extensions replaced. */
    public AgentState withExtensions(Map<String, Object> newExtensions) {
        Objects.requireNonNull(newExtensions, "newExtensions");
        return new AgentState(messages, files, newExtensions);
    }

    /** Return a copy with the given extension key set/replaced. */
    public AgentState withExtension(String key, Object value) {
        Objects.requireNonNull(key, "key");
        java.util.Map<String, Object> next = new java.util.LinkedHashMap<>(extensions);
        next.put(key, value);
        return new AgentState(messages, files, next);
    }
}
