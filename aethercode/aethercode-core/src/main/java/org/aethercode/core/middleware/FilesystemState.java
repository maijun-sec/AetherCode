package org.aethercode.core.middleware;

import org.aethercode.core.runtime.AgentState;

import java.util.Map;

/**
 * Marker state schema for the FilesystemMiddleware.
 *
 * <p>Java-native port of the Python
 * {@code deepagents.middleware.filesystem.FilesystemState} TypedDict
 * &mdash; an {@link AgentState} that carries a top-level
 * {@code files} channel for ephemeral filesystem contents. The
 * Java port stores files on the backend (not in the state
 * directly), but exposing this type as the
 * {@link FilesystemMiddleware#stateSchema()} value mirrors the
 * Python port's "in-process state files" behavior for the
 * {@code FilesystemState}-eligible backends ({@code StateBackend}
 * and {@code CompositeBackend} whose default is a
 * {@code StateBackend}).</p>
 *
 * <p>The Java port's {@link FilesystemState} is a thin record
 * carrying a {@code Map<String, FileData> files} field; the
 * runtime treats it as a marker type for tests and
 * {@code stateSchema} selection, not for in-memory storage.</p>
 */
public record FilesystemState(
        AgentState.AgentStateMap files
) {
    public FilesystemState {
        files = files == null ? new AgentState.AgentStateMap(Map.of()) : files;
    }

    /** Empty files map. */
    public static FilesystemState empty() {
        return new FilesystemState(new AgentState.AgentStateMap(Map.of()));
    }
}
