package org.aethercode.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.core.permission.PermissionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * per-session {@link PermissionMode} persistence. When the
 * daemon is started with {@code --sessions-dir}, every
 * {@code setPermissionMode} RPC call also writes
 * {@code <sessionsDir>/<sessionId>/permission-mode.json} so the
 * user's last-used mode survives engine restarts.
 *
 * <p>Mirrors {@link SkipConfirmationRegistry}'s persistence
 * pattern but lives in its own class because the two pieces
 * of state have different semantics (a counter vs an enum).
 * The on-disk format is:
 * <pre>
 *   { "mode": "ACCEPT_TASK", "updatedAt": 1734567890123 }
 * </pre>
 *
 * <p>Best-effort: a failed read or write logs at warn and
 * returns / no-ops. A missing file on a fresh engine returns
 * {@code null} (caller falls back to the build-time default).
 */
public final class PermissionModePersistence {

    private static final Logger LOG = LoggerFactory.getLogger(PermissionModePersistence.class);

    /** File name used for persistence. Public so tests can
     *  clean up the file by name. */
    public static final String PERSIST_FILE_NAME = "permission-mode.json";

    private PermissionModePersistence() {}

    /**
     * Read the persisted mode for a session. Returns
     * {@code null} if the file is missing, malformed, or
     * persistence is disabled. The {@code updatedAt} field
     * is ignored — only the mode name matters.
     */
    public static PermissionMode loadFromDisk(Path persistenceDir, String sessionId) {
        if (persistenceDir == null || sessionId == null) return null;
        Path file = persistenceDir.resolve(sessionId).resolve(PERSIST_FILE_NAME);
        if (!Files.exists(file)) return null;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = new ObjectMapper().readValue(Files.readString(file), Map.class);
            Object m = map.get("mode");
            if (m == null) return null;
            String name = m.toString().trim();
            if (name.isEmpty()) return null;
            try {
                return PermissionMode.valueOf(name);
            } catch (IllegalArgumentException iae) {
                LOG.warn("R105: unknown mode in {}: {} (ignoring)", file, name);
                return null;
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("R105: failed to load permission-mode for {}: {}",
                    sessionId, e.toString());
            return null;
        }
    }

    /**
     * Write the mode for a session. Best-effort: a failed
     * write logs at warn and returns. The on-disk file is
     * one line of JSON.
     */
    public static void saveToDisk(Path persistenceDir, String sessionId, PermissionMode mode) {
        if (persistenceDir == null || sessionId == null || mode == null) return;
        Path dir = persistenceDir.resolve(sessionId);
        Path file = dir.resolve(PERSIST_FILE_NAME);
        try {
            Files.createDirectories(dir);
            String json = new ObjectMapper().writeValueAsString(Map.of(
                    "mode", mode.name(),
                    "updatedAt", System.currentTimeMillis()
            ));
            Files.writeString(file, json);
        } catch (IOException | RuntimeException e) {
            LOG.warn("R105: failed to persist permission-mode for {}: {}",
                    sessionId, e.toString());
        }
    }
}
