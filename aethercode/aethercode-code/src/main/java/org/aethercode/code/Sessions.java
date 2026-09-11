package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Session persistence (stub for the SQLite-backed module).
 *
 * <p>Java-native port of the Python {@code deepagents_code.sessions}
 * module. The Java port exposes the public surface used by the TUI's
 * {@code /threads} screen; the full SQLite implementation lands with
 * the {@code deepagents-storage} port.</p>
 */
public final class Sessions {
    private Sessions() {}

    private static final Logger LOG = LoggerFactory.getLogger(Sessions.class);

    /** A persisted thread record. */
    public record ThreadInfo(
            String threadId,
            String agentName,
            long createdAtMs,
            long updatedAtMs,
            int messageCount,
            String initialPrompt) {
    }

    /** Return the path to the SQLite database. */
    public static Path databasePath() {
        return ModelConfig.DEFAULT_STATE_DIR.resolve("sessions.db");
    }

    /** Generate a new thread id. */
    public static String generateThreadId() {
        return java.util.UUID.randomUUID().toString();
    }

    /** List the most recent threads. */
    public static List<ThreadInfo> listRecent(int limit) {
        LOG.debug("Sessions.listRecent({}) (stub; full port uses SQLite)", limit);
        return List.of();
    }
}
