package org.aethercode.permission.migration;

import org.aethercode.permission.grants.Grant;
import org.aethercode.permission.migration.LegacyPermissionModeMigrator.LegacyMode;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * T-220..T-224 / design.md §3.1.3: outcome of a single
 * {@link LegacyPermissionModeMigrator#migrate} call.
 *
 * <p>Three terminal states:
 * <ul>
 *   <li>{@code MIGRATED} — a legacy file was found, parsed, and
 *       converted into a fresh {@code grants.json}. The legacy
 *       file was renamed to {@code .legacy}.</li>
 *   <li>{@code ALREADY_MIGRATED} — the legacy file is gone, but
 *       its {@code .legacy} sibling is present. This run was a
 *       no-op so the existing grants are untouched.</li>
 *   <li>{@code NO_LEGACY} — no legacy file was found at the
 *       given path. Nothing to do.</li>
 * </ul>
 *
 * <p>Backed by a Java sealed type (Java 21) so the caller is
 * forced to handle every case — a missing match in a switch is
 * a compile error.
 */
public sealed interface MigrationResult
        permits MigrationResult.Migrated,
                MigrationResult.AlreadyMigrated,
                MigrationResult.NoLegacy {

    /** What state we're in. */
    Status status();

    /** Convenience: grants written by this migration (empty for
     *  the {@code NO_LEGACY} and {@code ALREADY_MIGRATED}
     *  branches). */
    List<Grant> grants();

    /** The three terminal states. */
    enum Status {
        MIGRATED,
        ALREADY_MIGRATED,
        NO_LEGACY
    }

    /** No legacy file was found. */
    static MigrationResult noLegacy() {
        return NoLegacy.INSTANCE;
    }

    /** Already migrated; a no-op. */
    static MigrationResult alreadyMigrated() {
        return AlreadyMigrated.INSTANCE;
    }

    /** Successful migration. */
    static MigrationResult migrated(LegacyMode mode,
                                     List<Grant> grants,
                                     Path grantsFile,
                                     Path legacyFile) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(grants, "grants");
        Objects.requireNonNull(grantsFile, "grantsFile");
        Objects.requireNonNull(legacyFile, "legacyFile");
        return new Migrated(mode, List.copyOf(grants), grantsFile, legacyFile);
    }

    // ------------------------------------------------------------------
    //  Permits
    // ------------------------------------------------------------------

    /** A legacy file was converted into a {@code grants.json}.
     *
     *  <p>The interface methods are satisfied by the record's
     *  auto-generated accessors for the matching components
     *  ({@code grants()}). The other accessors have different
     *  shapes from the components, so they live as
     *  {@code *Opt()} methods. */
    record Migrated(LegacyMode mode,
                    List<Grant> grants,
                    Path grantsFile,
                    Path legacyFile)
            implements MigrationResult {

        public Migrated {
            grants = List.copyOf(grants);
            Objects.requireNonNull(grantsFile, "grantsFile");
            Objects.requireNonNull(legacyFile, "legacyFile");
        }

        @Override
        public Status status() { return Status.MIGRATED; }

        public Optional<LegacyMode> modeOpt() {
            return Optional.of(mode);
        }
        public Optional<Path> grantsFileOpt() {
            return Optional.of(grantsFile);
        }
        public Optional<Path> legacyFileOpt() {
            return Optional.of(legacyFile);
        }

        @Override
        public String toString() {
            return "Migrated{mode=" + mode + ", grants=" + grants.size()
                    + ", grantsFile=" + grantsFile + ", legacyFile=" + legacyFile + "}";
        }
    }

    /** Legacy was already migrated on a prior run. Singleton —
     *  there's no per-call data. */
    final class AlreadyMigrated implements MigrationResult {
        public static final AlreadyMigrated INSTANCE = new AlreadyMigrated();
        private AlreadyMigrated() {}
        @Override public Status status() { return Status.ALREADY_MIGRATED; }
        @Override public List<Grant> grants() { return List.of(); }
        @Override public String toString() { return "AlreadyMigrated{}"; }
    }

    /** No legacy file at the given path. Singleton. */
    final class NoLegacy implements MigrationResult {
        public static final NoLegacy INSTANCE = new NoLegacy();
        private NoLegacy() {}
        @Override public Status status() { return Status.NO_LEGACY; }
        @Override public List<Grant> grants() { return List.of(); }
        @Override public String toString() { return "NoLegacy{}"; }
    }
}
