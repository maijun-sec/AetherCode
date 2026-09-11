package org.aethercode.core.fs.backend;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Pluggable store for the {@code files} channel of the agent state.
 *
 * <p>Java-native equivalent of the langgraph
 * {@code CONFIG_KEY_READ}/{@code CONFIG_KEY_SEND} pair. The
 * {@link StateBackend} reads/writes the {@code files} map through this
 * interface; the default in-memory implementation lives in
 * {@code InMemoryFilesStateStore}, and the graph runtime can plug in a
 * channel-bound one.</p>
 */
public interface FilesStateStore {

    /**
     * Read the current files map.
     *
     * <p>{@code fresh} applies any pending task writes through the
     * channel's reducer before returning, giving read-your-writes
     * semantics within a single superstep — e.g. a tool that writes a
     * file and then reads it back.</p>
     */
    Map<String, FileData> read(boolean fresh);

    /** Queue a write to the files map. */
    void send(Map<String, FileData> update);
}
