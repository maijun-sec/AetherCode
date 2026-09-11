package org.aethercode.permission.migration;

import org.aethercode.permission.grants.Grant;
import org.aethercode.permission.grants.GrantDecision;
import org.aethercode.permission.grants.GrantScope;
import org.aethercode.permission.grants.GrantsFile;
import org.aethercode.permission.grants.GrantsStorage;
import org.aethercode.permission.migration.LegacyPermissionModeMigrator.LegacyMode;
import org.aethercode.permission.migration.MigrationResult.Migrated;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-225 / design.md §3.1.3: per-mode migration tests.
 *
 * <p>Coverage:
 * <ul>
 *   <li>missing legacy → {@link MigrationResult.NoLegacy}</li>
 *   <li>DEFAULT → no grants, grants.json empty</li>
 *   <li>READONLY → deny file.write, file.delete, shell.command</li>
 *   <li>ACCEPT_EDITS → allow file.write, file.delete</li>
 *   <li>auto-edit variant → same as ACCEPT_EDITS</li>
 *   <li>already-migrated file is detected and skipped</li>
 *   <li>legacy file is renamed to {@code .legacy}</li>
 *   <li>idempotent: a second run is a no-op</li>
 *   <li>unknown mode leaves the file alone + throws</li>
 *   <li>scope parameter flows into the synthesized grants</li>
 *   <li>lowercase / case-insensitive mode parsing</li>
 * </ul>
 */
class LegacyPermissionModeMigratorTest {

    @Test
    void missingLegacy_returnsNoLegacy(@TempDir Path tmp) {
        LegacyPermissionModeMigrator m = new LegacyPermissionModeMigrator();
        Path f = tmp.resolve("permission-mode.json");
        MigrationResult r = m.migrate(f, GrantScope.SESSION, "sess-1");
        assertEquals(MigrationResult.Status.NO_LEGACY, r.status());
        assertFalse(Files.exists(f));
        assertFalse(Files.exists(tmp.resolve("grants.json")));
    }

    @Test
    void defaultMode_writesEmptyGrantsAndRenamesLegacy(@TempDir Path tmp) throws Exception {
        Path legacy = writeLegacy(tmp, "DEFAULT", 1700000000000L);
        LegacyPermissionModeMigrator m = new LegacyPermissionModeMigrator();
        MigrationResult r = m.migrate(legacy, GrantScope.SESSION, "sess-1");
        assertEquals(MigrationResult.Status.MIGRATED, r.status());
        assertTrue(r instanceof Migrated, "expected Migrated branch");
        Migrated mig = (Migrated) r;
        assertEquals(LegacyMode.DEFAULT, mig.mode());
        assertEquals(0, mig.grants().size(), "DEFAULT must produce no grants");
        Path grantsFile = tmp.resolve("grants.json");
        assertTrue(Files.exists(grantsFile), "grants.json should exist");
        GrantsFile read = new GrantsStorage().read(grantsFile);
        assertEquals(0, read.grants().size());
        // Legacy renamed
        assertFalse(Files.exists(legacy));
        assertTrue(Files.exists(tmp.resolve("permission-mode.json.legacy")));
    }

    @Test
    void readonlyMode_deniesWritesAndShellCommand(@TempDir Path tmp) throws Exception {
        Path legacy = writeLegacy(tmp, "READONLY", 0L);
        LegacyPermissionModeMigrator m = new LegacyPermissionModeMigrator();
        MigrationResult r = m.migrate(legacy, GrantScope.SESSION, "sess-1");
        assertEquals(MigrationResult.Status.MIGRATED, r.status());
        Migrated mig = (Migrated) r;
        assertEquals(LegacyMode.READONLY, mig.mode());
        assertEquals(3, mig.grants().size());
        // Every grant is DENY at SESSION scope
        for (Grant g : mig.grants()) {
            assertEquals(GrantScope.SESSION, g.scope());
            assertEquals("sess-1", g.scopeId());
            assertEquals(GrantDecision.DENY, g.decision());
        }
        // Categories covered: file.write, file.delete, shell.command
        List<String> cats = mig.grants().stream().map(Grant::category).sorted().toList();
        assertEquals(List.of("file.delete", "file.write", "shell.command"), cats);
    }

    @Test
    void acceptEditsMode_allowsFileWritesAndDeletes(@TempDir Path tmp) throws Exception {
        Path legacy = writeLegacy(tmp, "ACCEPT_EDITS", 0L);
        LegacyPermissionModeMigrator m = new LegacyPermissionModeMigrator();
        MigrationResult r = m.migrate(legacy, GrantScope.PROJECT, "proj-1");
        assertEquals(MigrationResult.Status.MIGRATED, r.status());
        Migrated mig = (Migrated) r;
        assertEquals(LegacyMode.ACCEPT_EDITS, mig.mode());
        assertEquals(2, mig.grants().size());
        for (Grant g : mig.grants()) {
            assertEquals(GrantScope.PROJECT, g.scope());
            assertEquals("proj-1", g.scopeId());
            assertEquals(GrantDecision.ALLOW, g.decision());
        }
        List<String> cats = mig.grants().stream().map(Grant::category).sorted().toList();
        assertEquals(List.of("file.delete", "file.write"), cats);
    }

    @Test
    void autoEditVariant_accepted(@TempDir Path tmp) throws Exception {
        Path legacy = writeLegacy(tmp, "AUTO_EDIT", 0L);
        LegacyPermissionModeMigrator m = new LegacyPermissionModeMigrator();
        MigrationResult r = m.migrate(legacy, GrantScope.PROJECT, "p");
        Migrated mig = (Migrated) r;
        assertEquals(LegacyMode.AUTO_EDIT, mig.mode());
        assertEquals(2, mig.grants().size());
    }

    @Test
    void alreadyMigrated_isANoOp(@TempDir Path tmp) throws Exception {
        Path legacy = writeLegacy(tmp, "ACCEPT_EDITS", 0L);
        // First run.
        new LegacyPermissionModeMigrator().migrate(legacy, GrantScope.PROJECT, "p");
        // Second run on the same path — the legacy file is
        // already gone (renamed to .legacy) and grants.json
        // exists.
        Path grants = tmp.resolve("grants.json");
        long sizeBefore = Files.size(grants);
        long mtimeBefore = Files.getLastModifiedTime(grants).toMillis();
        Thread.sleep(10); // ensure clock advances
        MigrationResult r2 = new LegacyPermissionModeMigrator().migrate(legacy, GrantScope.PROJECT, "p");
        assertEquals(MigrationResult.Status.ALREADY_MIGRATED, r2.status(),
                "the .legacy sibling signals the previous migration");
        // The pre-existing grants.json is untouched.
        assertEquals(sizeBefore, Files.size(grants));
        assertEquals(mtimeBefore, Files.getLastModifiedTime(grants).toMillis());
    }

    @Test
    void alreadyMigratedMarker_isDetected(@TempDir Path tmp) throws Exception {
        // Set up a state where the legacy file was renamed
        // BEFORE the migrator runs (e.g. a previous interrupted
        // attempt).
        writeLegacy(tmp, "ACCEPT_EDITS", 0L);
        Path legacy = tmp.resolve("permission-mode.json");
        Files.move(legacy, tmp.resolve("permission-mode.json.legacy"));
        MigrationResult r = new LegacyPermissionModeMigrator()
                .migrate(legacy, GrantScope.PROJECT, "p");
        assertEquals(MigrationResult.Status.ALREADY_MIGRATED, r.status());
    }

    @Test
    void unknownMode_throwsAndLeavesFileAlone(@TempDir Path tmp) throws Exception {
        Path legacy = writeLegacy(tmp, "WAT", 0L);
        LegacyPermissionModeMigrator m = new LegacyPermissionModeMigrator();
        try {
            m.migrate(legacy, GrantScope.SESSION, "sess-1");
            assertFalse(true, "expected an exception for an unknown mode");
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage().contains("unknown legacy permission-mode"));
        }
        // The legacy file is left in place so the user can fix it.
        assertTrue(Files.exists(legacy));
        assertFalse(Files.exists(tmp.resolve("grants.json")));
    }

    @Test
    void scopeAndScopeIdFlowIntoGrants(@TempDir Path tmp) throws Exception {
        Path legacy = writeLegacy(tmp, "READONLY", 0L);
        MigrationResult r = new LegacyPermissionModeMigrator()
                .migrate(legacy, GrantScope.USER, "global");
        Migrated mig = (Migrated) r;
        for (Grant g : mig.grants()) {
            assertEquals(GrantScope.USER, g.scope());
            assertEquals("global", g.scopeId());
        }
    }

    @Test
    void modeStringIsCaseInsensitive(@TempDir Path tmp) throws Exception {
        Path legacy = writeLegacy(tmp, "default", 0L);
        MigrationResult r = new LegacyPermissionModeMigrator()
                .migrate(legacy, GrantScope.SESSION, "sess-1");
        Migrated mig = (Migrated) r;
        assertEquals(LegacyMode.DEFAULT, mig.mode());
    }

    @Test
    void isLegacy_detectsLegacyShape(@TempDir Path tmp) throws Exception {
        Path legacy = writeLegacy(tmp, "DEFAULT", 0L);
        LegacyPermissionModeMigrator m = new LegacyPermissionModeMigrator();
        assertTrue(m.isLegacy(legacy));
    }

    @Test
    void isLegacy_rejectsGrantsFile(@TempDir Path tmp) throws Exception {
        // A grants.json must not be misidentified as a legacy
        // permission-mode.json (would cause an infinite loop on
        // re-migration).
        Path grants = tmp.resolve("grants.json");
        Files.writeString(grants, "{\n  \"schemaVersion\": 1,\n  \"grants\": []\n}\n");
        LegacyPermissionModeMigrator m = new LegacyPermissionModeMigrator();
        assertFalse(m.isLegacy(grants));
    }

    @Test
    void isLegacy_rejectsMissingFile(@TempDir Path tmp) {
        Path legacy = tmp.resolve("permission-mode.json");
        assertFalse(new LegacyPermissionModeMigrator().isLegacy(legacy));
    }

    @Test
    void blankScopeIdRejected(@TempDir Path tmp) {
        Path legacy = tmp.resolve("permission-mode.json");
        try {
            new LegacyPermissionModeMigrator()
                    .migrate(legacy, GrantScope.SESSION, "  ");
            assertFalse(true, "expected IllegalArgumentException for blank scopeId");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("scopeId"));
        }
    }

    @Test
    void updatedAtPreservedOnGrants(@TempDir Path tmp) throws Exception {
        long ts = 1700000000000L;
        Path legacy = writeLegacy(tmp, "READONLY", ts);
        MigrationResult r = new LegacyPermissionModeMigrator()
                .migrate(legacy, GrantScope.SESSION, "sess-1");
        Migrated mig = (Migrated) r;
        for (Grant g : mig.grants()) {
            assertEquals(ts, g.createdAt(),
                    "migrated grants should inherit the legacy updatedAt");
        }
    }

    @Test
    void noUpdatedAt_fallsBackToNow(@TempDir Path tmp) throws Exception {
        // Legacy file without updatedAt → grants get a fresh createdAt
        // (i.e. not the epoch 0 or any other fixed value).
        Path legacy = writeLegacy(tmp, "READONLY", 0L); // 0 = no updatedAt
        long before = System.currentTimeMillis();
        MigrationResult r = new LegacyPermissionModeMigrator()
                .migrate(legacy, GrantScope.SESSION, "sess-1");
        long after = System.currentTimeMillis();
        Migrated mig = (Migrated) r;
        for (Grant g : mig.grants()) {
            assertTrue(g.createdAt() >= before && g.createdAt() <= after,
                    "fresh createdAt when updatedAt missing; got " + g.createdAt());
        }
    }

    // ------------------------------------------------------------------
    //  helpers
    // ------------------------------------------------------------------

    private static Path writeLegacy(Path dir, String mode, long updatedAt) throws Exception {
        Path f = dir.resolve("permission-mode.json");
        String json;
        if (updatedAt > 0L) {
            json = "{\n  \"mode\": \"" + mode + "\",\n  \"updatedAt\": " + updatedAt + "\n}\n";
        } else {
            json = "{\n  \"mode\": \"" + mode + "\"\n}\n";
        }
        Files.writeString(f, json, StandardCharsets.UTF_8);
        assertNotNull(f);
        return f;
    }
}
