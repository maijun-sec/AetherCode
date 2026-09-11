package org.aethercode.permission.migration;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aethercode.permission.grants.Grant;
import org.aethercode.permission.grants.GrantDecision;
import org.aethercode.permission.grants.GrantScope;
import org.aethercode.permission.grants.GrantsFile;
import org.aethercode.permission.grants.GrantsStorage;
import org.aethercode.permission.grants.GrantsStorage.GrantsFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * T-220..T-224 / design.md §3.1.3: migrate a legacy
 * {@code permission-mode.json} into the new {@code grants.json}
 * format.
 *
 * <p>Legacy shape (per the in-tree
 * {@code PermissionModePersistence} writer):
 * <pre>
 *   { "mode": "ACCEPT_TASK", "updatedAt": 1734567890123 }
 * </pre>
 *
 * <p>On {@link #migrate(Path, GrantScope, String)}, the migrator:
 * <ol>
 *   <li>Reads the legacy file. If missing, returns
 *       {@link MigrationResult#noLegacy()}.</li>
 *   <li>Synthesizes a default grant set per the legacy mode (see
 *       {@link LegacyMode#defaultGrants}):</li>
 *   <li>Writes the new {@code grants.json} next to it via
 *       {@link GrantsStorage#write}.</li>
 *   <li>Renames the legacy file to {@code permission-mode.json.legacy}
 *       atomically (falls back to non-atomic replace on filesystems
 *       that reject {@code ATOMIC_MOVE}, e.g. some FAT mounts).</li>
 * </ol>
 *
 * <p>Idempotent: re-running the migrator on a file that has already
 * been renamed is a no-op (returns {@link MigrationResult#noLegacy()}
 * or {@link MigrationResult#alreadyMigrated()}) and does NOT
 * clobber any new grants the user has accumulated since the first
 * migration.
 */
public final class LegacyPermissionModeMigrator {

    private static final Logger LOG = LoggerFactory.getLogger(LegacyPermissionModeMigrator.class);

    /** Wire name of the legacy file (kept in sync with
     *  {@code PermissionModePersistence.PERSIST_FILE_NAME}). */
    public static final String LEGACY_FILE_NAME = "permission-mode.json";

    /** Suffix appended to the legacy file after a successful
     *  migration, per design.md §3.1.3 step 4. */
    public static final String LEGACY_SUFFIX = ".legacy";

    private final GrantsStorage storage;
    private final ObjectMapper mapper;

    public LegacyPermissionModeMigrator() {
        this(new GrantsStorage(), new ObjectMapper());
    }

    /** Constructor for tests — allows custom storage (custom
     *  rotation threshold) and a mapper configured for the
     *  caller's needs. */
    public LegacyPermissionModeMigrator(GrantsStorage storage, ObjectMapper mapper) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Detect whether {@code legacyFile} is a legacy
     * {@code permission-mode.json}. The file must exist and parse
     * as the legacy shape ({@code mode} + optional
     * {@code updatedAt}). Files with a {@code schemaVersion} field
     * are treated as already-migrated and return {@code false}.
     */
    public boolean isLegacy(Path legacyFile) {
        Objects.requireNonNull(legacyFile, "legacyFile");
        if (!Files.exists(legacyFile)) return false;
        if (!Files.isRegularFile(legacyFile)) return false;
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> map = mapper.readValue(
                    Files.readString(legacyFile), java.util.Map.class);
            if (map == null) return false;
            // Already-migrated files have schemaVersion=1 and no "mode"
            if (map.containsKey("schemaVersion")) return false;
            return map.containsKey("mode");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Has {@code legacyFile} already been renamed to
     * {@code .legacy}? Useful so a follow-up run can short-circuit.
     */
    public boolean alreadyMigrated(Path legacyFile) {
        Objects.requireNonNull(legacyFile, "legacyFile");
        Path renamed = legacyFile.resolveSibling(legacyFile.getFileName().toString() + LEGACY_SUFFIX);
        return Files.exists(renamed);
    }

    /**
     * Run the migration.
     *
     * @param legacyFile     the {@code permission-mode.json} to migrate
     * @param targetScope    the {@link GrantScope} the synthesized
     *                       grants should belong to. The migrator
     *                       writes them to {@code grants.json} in the
     *                       same directory as {@code legacyFile}, so
     *                       the caller picks the scope based on
     *                       where the legacy file lived (session vs
     *                       project vs user).
     * @param scopeId        the {@code scopeId} for the synthesized
     *                       grants (e.g. session id, project id,
     *                       or {@code "global"} for user).
     * @return the {@link MigrationResult} describing what happened
     * @throws GrantsFileException if the legacy file is corrupt or
     *         cannot be parsed into a known mode
     */
    public MigrationResult migrate(Path legacyFile, GrantScope targetScope, String scopeId) {
        Objects.requireNonNull(legacyFile, "legacyFile");
        Objects.requireNonNull(targetScope, "targetScope");
        Objects.requireNonNull(scopeId, "scopeId");
        if (scopeId.isBlank()) {
            throw new IllegalArgumentException("scopeId must not be blank");
        }
        if (alreadyMigrated(legacyFile)) {
            return MigrationResult.alreadyMigrated();
        }
        if (!Files.exists(legacyFile)) {
            return MigrationResult.noLegacy();
        }
        if (!isLegacy(legacyFile)) {
            // The file exists but doesn't look like a legacy
            // permission-mode.json. Don't touch it.
            return MigrationResult.noLegacy();
        }
        LegacyPayload payload = readLegacy(legacyFile);
        LegacyMode mode = payload.toMode();
        if (mode == null) {
            // Unknown mode string — surface to the user, leave the
            // legacy file alone so they can fix it.
            throw new GrantsFileException(
                    "unknown legacy permission-mode: " + payload.mode);
        }
        List<Grant> grants = mode.defaultGrants(targetScope, scopeId, payload.updatedAt);
        GrantsFile file = GrantsFile.empty();
        for (Grant g : grants) {
            file = file.append(g);
        }
        Path grantsFile = legacyFile.resolveSibling(GrantsFile.FILE_NAME);
        storage.write(grantsFile, file);
        renameToLegacy(legacyFile);
        LOG.info("R-migration: {} mode={} -> {} ({} grants)",
                legacyFile, mode, grantsFile, grants.size());
        return MigrationResult.migrated(mode, grants, grantsFile,
                legacyFile.resolveSibling(legacyFile.getFileName().toString() + LEGACY_SUFFIX));
    }

    private LegacyPayload readLegacy(Path legacyFile) {
        try {
            return mapper.readValue(Files.readString(legacyFile), LegacyPayload.class);
        } catch (IOException ioe) {
            throw new GrantsFileException("read legacy failed: " + legacyFile, ioe);
        }
    }

    private void renameToLegacy(Path legacyFile) {
        Path target = legacyFile.resolveSibling(
                legacyFile.getFileName().toString() + LEGACY_SUFFIX);
        try {
            Files.move(legacyFile, target,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException amns) {
            try {
                Files.move(legacyFile, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ioe) {
                throw new GrantsFileException(
                        "rename to .legacy failed: " + legacyFile, ioe);
            }
        } catch (IOException ioe) {
            throw new GrantsFileException(
                    "rename to .legacy failed: " + legacyFile, ioe);
        }
    }

    // ------------------------------------------------------------------
    //  T-221 / T-222 / T-223 — per-mode default grant sets
    //  See design.md §3.1.3 step 2.
    // ------------------------------------------------------------------

    /** The four legacy modes the migration handles. The migrator
     *  treats unknown modes as "no grants" (the safe default —
     *  same as {@link #DEFAULT}) so a future mode added to
     *  {@code PermissionMode} doesn't crash older clients; it
     *  just gets a less ergonomic migration. */
    public enum LegacyMode {
        /** T-221: no grants — the user gets prompted for every
         *  non-low-risk call. */
        DEFAULT,
        /** T-222: deny {@code file.write}, {@code file.delete},
         *  {@code shell.command}. */
        READONLY,
        /** T-223: allow {@code file.write} and {@code file.delete},
         *  prompt the rest. */
        ACCEPT_EDITS,
        /** "auto" mode (newer name for ACCEPT_EDITS) — same
         *  defaults. The legacy {@code PermissionMode} enum does
         *  not include this name; the migrator accepts it so a
         *  future migration path doesn't need a code change. */
        AUTO_EDIT;

        /** Read the wire string case-insensitively. Accepts both
         *  {@code "DEFAULT"} and {@code "default"}; the legacy
         *  writer uses uppercase, but a hand-edited file might
         *  be lowercased. Returns {@code null} for an unknown
         *  mode so the caller can decide. */
        public static LegacyMode fromWire(String s) {
            if (s == null) return null;
            String norm = s.trim().toUpperCase(java.util.Locale.ROOT);
            return switch (norm) {
                case "DEFAULT" -> DEFAULT;
                case "READONLY", "READ_ONLY" -> READONLY;
                case "ACCEPT_EDITS" -> ACCEPT_EDITS;
                case "AUTO_EDIT" -> AUTO_EDIT;
                default -> null;
            };
        }

        /** Per design.md §3.1.3 step 2. {@code originTs} is the
         *  {@code updatedAt} from the legacy file (so the
         *  migrated grants inherit the same timestamp rather
         *  than the migration time). */
        public List<Grant> defaultGrants(GrantScope scope, String scopeId, long originTs) {
            List<Grant> out = new ArrayList<>();
            switch (this) {
                case DEFAULT -> {
                    // No grants — see design.md §3.1.3 step 2
                    // (DEFAULT → no grants).
                }
                case READONLY -> {
                    // Deny file.write, file.delete, shell.command
                    out.add(synthDeny(scope, scopeId, "file.write", originTs));
                    out.add(synthDeny(scope, scopeId, "file.delete", originTs));
                    out.add(synthDeny(scope, scopeId, "shell.command", originTs));
                }
                case ACCEPT_EDITS, AUTO_EDIT -> {
                    // Allow file.write and file.delete, prompt the rest
                    out.add(synthAllow(scope, scopeId, "file.write", originTs));
                    out.add(synthAllow(scope, scopeId, "file.delete", originTs));
                }
            }
            return out;
        }

        private static Grant synthAllow(GrantScope scope, String scopeId,
                                        String category, long ts) {
            return new Grant(
                    Grant.newId(),
                    scope,
                    scopeId,
                    category,
                    GrantDecision.ALLOW,
                    "migrated from " + LEGACY_FILE_NAME,
                    ts > 0 ? ts : System.currentTimeMillis(),
                    null);
        }

        private static Grant synthDeny(GrantScope scope, String scopeId,
                                       String category, long ts) {
            return new Grant(
                    Grant.newId(),
                    scope,
                    scopeId,
                    category,
                    GrantDecision.DENY,
                    "migrated from " + LEGACY_FILE_NAME,
                    ts > 0 ? ts : System.currentTimeMillis(),
                    null);
        }
    }

    /** On-the-wire shape of {@code permission-mode.json}. Only
     *  fields the migrator actually needs are declared; everything
     *  else is ignored so the file is forward-compatible. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class LegacyPayload {
        @JsonProperty("mode")
        public final String mode;
        @JsonProperty("updatedAt")
        public final long updatedAt;

        @JsonCreator
        public LegacyPayload(@JsonProperty("mode") String mode,
                             @JsonProperty("updatedAt") Long updatedAt) {
            this.mode = mode;
            this.updatedAt = updatedAt == null ? 0L : updatedAt;
        }

        LegacyMode toMode() {
            return LegacyMode.fromWire(mode);
        }
    }
}
