package org.aethercode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tests for {@link SkipConfirmationRegistry} persistence.
 * The registry writes a JSON file under
 * {@code <persistenceDir>/<sessionId>/skip-confirmation.json}
 * on every set/consume, and {@link SkipConfirmationRegistry#loadFromDisk}
 * reads it back at engine boot.
 */
class SkipConfirmationRegistryPersistenceTest {

    private static AetherCodeConfig.MemoryConfig defaultMemory() {
        return new AetherCodeConfig.MemoryConfig();
    }

    @Test
    void setWritesFileToDisk(@TempDir Path tmp) throws Exception {
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry(tmp);
        reg.set("s1", 5);
        Path file = tmp.resolve("s1/skip-confirmation.json");
        assertThat(Files.exists(file))
                .as("set(5) writes the file")
                .isTrue();
        String json = Files.readString(file);
        assertThat(json).contains("\"remaining\":5");
    }

    @Test
    void consumePersistsDecrementedValue(@TempDir Path tmp) throws Exception {
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry(tmp);
        reg.set("s1", 3);
        assertThat(reg.consumeOne("s1")).isTrue();
        assertThat(reg.consumeOne("s1")).isTrue();
        Path file = tmp.resolve("s1/skip-confirmation.json");
        String json = Files.readString(file);
        assertThat(json).contains("\"remaining\":1");
    }

    @Test
    void clearDeletesCounterAndPersistsZero(@TempDir Path tmp) throws Exception {
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry(tmp);
        reg.set("s1", 5);
        reg.clear("s1");
        assertThat(reg.remaining("s1")).isEqualTo(0);
        // The file is still on disk (intentional 闁?easier to
        // debug "what was the last value?" post-mortem) but
        // contains remaining=0.
        Path file = tmp.resolve("s1/skip-confirmation.json");
        assertThat(Files.exists(file)).isTrue();
        String json = Files.readString(file);
        assertThat(json).contains("\"remaining\":0");
    }

    @Test
    void loadFromDiskRestoresCounter(@TempDir Path tmp) throws Exception {
        // First "engine" writes a counter.
        SkipConfirmationRegistry w = new SkipConfirmationRegistry(tmp);
        w.set("s1", 7);
        // Second "engine" boots, loads the counter.
        SkipConfirmationRegistry r = new SkipConfirmationRegistry(tmp);
        int loaded = r.loadFromDisk("s1");
        assertThat(loaded).isEqualTo(7);
        assertThat(r.remaining("s1")).isEqualTo(7);
    }

    @Test
    void loadFromDiskMissingFile_returnsZero(@TempDir Path tmp) {
        SkipConfirmationRegistry r = new SkipConfirmationRegistry(tmp);
        int loaded = r.loadFromDisk("never-written");
        assertThat(loaded).isEqualTo(0);
    }

    @Test
    void loadFromDiskMalformedJson_returnsZero(@TempDir Path tmp) throws Exception {
        // Pre-create a malformed file.
        Path dir = tmp.resolve("s1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SkipConfirmationRegistry.PERSIST_FILE_NAME),
                "this is not json { {");
        SkipConfirmationRegistry r = new SkipConfirmationRegistry(tmp);
        int loaded = r.loadFromDisk("s1");
        assertThat(loaded)
                .as("malformed JSON fails over to 0, no exception")
                .isEqualTo(0);
    }

    @Test
    void loadFromDiskZeroValue_clearsInMemoryCounter(@TempDir Path tmp) throws Exception {
        // File says remaining=0; loadFromDisk should NOT
        // resurrect a counter (it cleared on the previous
        // engine exit, the file is a tombstone).
        Path dir = tmp.resolve("s1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SkipConfirmationRegistry.PERSIST_FILE_NAME),
                "{\"remaining\":0,\"updatedAt\":0}");
        SkipConfirmationRegistry r = new SkipConfirmationRegistry(tmp);
        int loaded = r.loadFromDisk("s1");
        assertThat(loaded).isEqualTo(0);
        assertThat(r.remaining("s1")).isEqualTo(0);
    }

    @Test
    void noPersistenceDir_inMemoryOnly(@TempDir Path tmp) {
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry();
        reg.set("s1", 5);
        assertThat(reg.remaining("s1")).isEqualTo(5);
        // loadFromDisk is a no-op when persistenceDir is null.
        assertThat(reg.loadFromDisk("s1")).isEqualTo(0);
    }

    @Test
    void applyDefaultPersists(@TempDir Path tmp) throws Exception {
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry(tmp);
        AetherCodeConfig cfg = new AetherCodeConfig(1, new PermissionMatrix(),
                false, 4, "design-first", null, 5, defaultMemory());
        reg.applyDefault("s1", cfg);
        Path file = tmp.resolve("s1/skip-confirmation.json");
        assertThat(Files.exists(file))
                .as("applyDefault writes the file when persistence is enabled")
                .isTrue();
        assertThat(reg.remaining("s1")).isEqualTo(4);
    }

    @Test
    void infiniteCounter_persistsAsMaxValue(@TempDir Path tmp) throws Exception {
        SkipConfirmationRegistry reg = new SkipConfirmationRegistry(tmp);
        AetherCodeConfig cfg = new AetherCodeConfig(1, new PermissionMatrix(),
                true, 0, "design-first", null, 5, defaultMemory());
        reg.applyDefault("s1", cfg);
        Path file = tmp.resolve("s1/skip-confirmation.json");
        String json = Files.readString(file);
        // Integer.MAX_VALUE serialises to 2147483647.
        assertThat(json).contains("\"remaining\":2147483647");
    }
}
