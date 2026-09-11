package org.aethercode.config;

import org.aethercode.core.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tests for {@link PermissionModePersistence}. The format
 * is one line of JSON with { mode, updatedAt }. Missing /
 * malformed / unknown-mode values all fall back to {@code null}
 * (caller uses the build-time default).
 */
class PermissionModePersistenceTest {

    @Test
    void saveThenLoad_roundTrips(@TempDir Path tmp) {
        PermissionModePersistence.saveToDisk(tmp, "s1", PermissionMode.ACCEPT_TASK);
        PermissionMode loaded = PermissionModePersistence.loadFromDisk(tmp, "s1");
        assertThat(loaded).isEqualTo(PermissionMode.ACCEPT_TASK);
    }

    @Test
    void saveAllSixModes(@TempDir Path tmp) {
        for (PermissionMode m : PermissionMode.values()) {
            PermissionModePersistence.saveToDisk(tmp, "s-" + m.name(), m);
            assertThat(PermissionModePersistence.loadFromDisk(tmp, "s-" + m.name()))
                    .as("round-trip for " + m)
                    .isEqualTo(m);
        }
    }

    @Test
    void loadFromDisk_missingFile_returnsNull(@TempDir Path tmp) {
        assertThat(PermissionModePersistence.loadFromDisk(tmp, "never-saved")).isNull();
    }

    @Test
    void loadFromDisk_malformedJson_returnsNull(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("s1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(PermissionModePersistence.PERSIST_FILE_NAME),
                "this is not json { {");
        assertThat(PermissionModePersistence.loadFromDisk(tmp, "s1")).isNull();
    }

    @Test
    void loadFromDisk_unknownMode_returnsNull(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("s1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(PermissionModePersistence.PERSIST_FILE_NAME),
                "{\"mode\":\"NOT_A_MODE\",\"updatedAt\":0}");
        assertThat(PermissionModePersistence.loadFromDisk(tmp, "s1"))
                .as("unknown mode name fails over to null")
                .isNull();
    }

    @Test
    void loadFromDisk_missingModeField_returnsNull(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("s1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(PermissionModePersistence.PERSIST_FILE_NAME),
                "{\"updatedAt\":0}");
        assertThat(PermissionModePersistence.loadFromDisk(tmp, "s1")).isNull();
    }

    @Test
    void nullPersistenceDir_isNoOp(@TempDir Path tmp) {
        // saveToDisk with null dir does not throw.
        PermissionModePersistence.saveToDisk(null, "s1", PermissionMode.DEFAULT);
        // loadFromDisk with null dir returns null.
        assertThat(PermissionModePersistence.loadFromDisk(null, "s1")).isNull();
    }

    @Test
    void nullSessionId_isNoOp(@TempDir Path tmp) {
        PermissionModePersistence.saveToDisk(tmp, null, PermissionMode.DEFAULT);
        assertThat(PermissionModePersistence.loadFromDisk(tmp, null)).isNull();
    }

    @Test
    void saveCreatesDirectoryIfMissing(@TempDir Path tmp) {
        // sessionsDir exists, but <sessionsDir>/<sessionId> does not.
        Path sessionDir = tmp.resolve("brand-new-session");
        assertThat(Files.exists(sessionDir)).isFalse();
        PermissionModePersistence.saveToDisk(tmp, "brand-new-session", PermissionMode.ACCEPT_EDITS);
        assertThat(Files.exists(sessionDir)).isTrue();
        assertThat(Files.exists(sessionDir.resolve(PermissionModePersistence.PERSIST_FILE_NAME))).isTrue();
    }

    @Test
    void overwritesPreviousFile(@TempDir Path tmp) {
        PermissionModePersistence.saveToDisk(tmp, "s1", PermissionMode.ACCEPT_TASK);
        PermissionModePersistence.saveToDisk(tmp, "s1", PermissionMode.BYPASS_PERMISSIONS);
        // The second save replaces the first; only the latest value
        // is on disk.
        assertThat(PermissionModePersistence.loadFromDisk(tmp, "s1"))
                .isEqualTo(PermissionMode.BYPASS_PERMISSIONS);
    }
}
