package org.aethercode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConfigEngine#loadFrom(Path)} filesystem behaviour.
 * Missing file -> defaults. Bad JSON -> defaults. Valid file -> parsed.
 */
class ConfigEngineTest {

    @Test
    void loadFrom_missingFile_returnsDefaults(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist.json");
        AetherCodeConfig c = ConfigEngine.loadFrom(missing);
        assertThat(c.workflow).isEqualTo("design-first");
        assertThat(c.permissionMatrix.toolNames())
                .contains("file_read", "file_write", "bash");
    }

    @Test
    void loadFrom_nullPath_returnsDefaults() {
        AetherCodeConfig c = ConfigEngine.loadFrom(null);
        assertThat(c.workflow).isEqualTo("design-first");
    }

    @Test
    void loadFrom_validConfig_overridesMatrix(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve(".aethercode/config.json");
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, """
                {
                  "version": 1,
                  "workflow": "legacy",
                  "skipConfirmation": true,
                  "permissionMatrix": {
                    "file_write": {
                      "*": { "*": "ALLOW" }
                    }
                  }
                }
                """);
        AetherCodeConfig c = ConfigEngine.loadFrom(cfg);
        assertThat(c.workflow).isEqualTo("legacy");
        assertThat(c.skipConfirmation).isTrue();
        Action a = c.permissionMatrix.lookup("file_write", "src/main/java/Foo.java", OpKind.CREATE);
        assertThat(a).isEqualTo(Action.ALLOW);
    }

    @Test
    void loadFrom_garbageJson_returnsDefaultsAndDoesNotThrow(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve("config.json");
        Files.writeString(cfg, "this is not json { {");
        AetherCodeConfig c = ConfigEngine.loadFrom(cfg);
        assertThat(c.workflow).isEqualTo("design-first");
    }

    @Test
    void loadFrom_emptyFile_returnsDefaults(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve("config.json");
        Files.writeString(cfg, "");
        AetherCodeConfig c = ConfigEngine.loadFrom(cfg);
        assertThat(c.workflow).isEqualTo("design-first");
    }

    @Test
    void loadFromProjectRoot_resolvesDotAethercodeConfigJson(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve(".aethercode/config.json");
        Files.createDirectories(cfg.getParent());
        Files.writeString(cfg, """
                {
                  "version": 1,
                  "workflow": "legacy"
                }
                """);
        AetherCodeConfig c = ConfigEngine.loadFromProjectRoot(tmp);
        assertThat(c.workflow).isEqualTo("legacy");
    }

    @Test
    void loadFromProjectRoot_nullRoot_returnsDefaults() {
        AetherCodeConfig c = ConfigEngine.loadFromProjectRoot(null);
        assertThat(c.workflow).isEqualTo("design-first");
    }
}
