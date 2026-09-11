package org.aethercode.code;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * One-time migration of legacy state files into {@code ~/.deepagents/.state/}.
 *
 * <p>Java-native port of the Python {@code deepagents_code.state_migration}
 * module. The migration is best-effort and idempotent: it skips entries
 * whose destination already exists, logs and continues on per-entry
 * failures, and never blocks startup on I/O errors.</p>
 */
public final class StateMigration {
    private StateMigration() {}

    private static final Logger LOG = LoggerFactory.getLogger(StateMigration.class);

    /** Marker filename copied into the new state directory. */
    public static final String ONBOARDING_MARKER_FILENAME = ".onboarded";

    /**
     * Names directly under {@code ~/.deepagents/} that now live in
     * {@code .state/}. {@code sessions.db-wal} and {@code sessions.db-shm}
     * are SQLite sidecar files that may or may not be present depending on
     * whether the database was opened in WAL mode and whether a
     * checkpoint had run before shutdown.
     */
    public static final List<String> LEGACY_NAMES = List.of(
            "mcp-tokens",
            "sessions.db",
            "sessions.db-wal",
            "sessions.db-shm",
            "latest_version.json",
            "update_state.json",
            "history.jsonl",
            ONBOARDING_MARKER_FILENAME);

    /**
     * Move legacy state entries from {@code configDir} into {@code stateDir}.
     * Idempotent: each entry is skipped when the destination already exists
     * or when the source does not exist. Errors on individual entries are
     * logged and swallowed.
     *
     * @param configDir directory holding legacy state
     * @param stateDir  destination directory for state files
     */
    public static void migrateLegacyState(Path configDir, Path stateDir) {
        if (configDir == null || stateDir == null) {
            return;
        }
        if (!Files.isDirectory(configDir)) {
            return;
        }
        List<Migration> pending = new ArrayList<>();
        for (String name : LEGACY_NAMES) {
            Path src = configDir.resolve(name);
            Path dst = stateDir.resolve(name);
            if (!Files.exists(src)) {
                continue;
            }
            if (Files.exists(dst)) {
                LOG.warn("Cannot migrate {} -> {}: destination already exists. "
                        + "Inspect both files and either delete the obsolete one "
                        + "or move the legacy file in manually.", src, dst);
                continue;
            }
            pending.add(new Migration(src, dst));
        }
        if (pending.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(stateDir);
        } catch (IOException e) {
            LOG.warn("Could not create state directory {}; skipping state migration",
                    stateDir, e);
            return;
        }
        for (Migration m : pending) {
            try {
                Files.move(m.src, m.dst, StandardCopyOption.ATOMIC_MOVE);
                LOG.info("Migrated {} -> {}", m.src, m.dst);
            } catch (IOException e) {
                LOG.warn("Failed to migrate {} -> {}; leaving legacy file in place",
                        m.src, m.dst, e);
            }
        }
    }

    private record Migration(Path src, Path dst) {}
}
