package org.aethercode.config;

import org.aethercode.core.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * contract test for the new {@code defaultPermissionMode}
 * project-config field.
 *
 * <p>The user can now write
 * <pre>
 *   { "defaultPermissionMode": "ASK_BEFORE_TOOL" }
 * </pre>
 * in {@code .aethercode/config.json} and the engine will boot
 * with that mode instead of the suggester's recommendation.
 * Case-insensitive, hyphens / underscores both accepted.
 *
 * <p>Also pins the suggester's new default — empty / exploration
 * projects get {@code ASK_BEFORE_TOOL} (was {@code DEFAULT} legacy,
 * but the names are semantically identical; the new name is just
 * more discoverable in the UI).
 */
class AetherCodeConfigR163Test {

    @Test
    void defaultsHasNullDefaultPermissionMode(@TempDir Path cwd) {
        // the built-in default is null = "let the suggester
        // pick". A null field means we do NOT pre-pin a mode;
        // a non-null field would override the suggester.
        AetherCodeConfig c = AetherCodeConfig.defaults();
        assertThat(c.defaultPermissionMode).isNull();
    }

    @Test
    void configEngineReadsDefaultPermissionMode_askBeforeTool(@TempDir Path cwd) throws IOException {
        Path configFile = cwd.resolve(".aethercode").resolve("config.json");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile,
                "{\n" +
                "  \"version\": 1,\n" +
                "  \"defaultPermissionMode\": \"ASK_BEFORE_TOOL\"\n" +
                "}\n");
        AetherCodeConfig c = ConfigEngine.loadFrom(configFile);
        assertThat(c.defaultPermissionMode).isEqualTo("ASK_BEFORE_TOOL");
    }

    @Test
    void configEngineReadsDefaultPermissionMode_acceptTask(@TempDir Path cwd) throws IOException {
        Path configFile = cwd.resolve(".aethercode").resolve("config.json");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile,
                "{\n" +
                "  \"defaultPermissionMode\": \"ACCEPT_TASK\"\n" +
                "}\n");
        AetherCodeConfig c = ConfigEngine.loadFrom(configFile);
        assertThat(c.defaultPermissionMode).isEqualTo("ACCEPT_TASK");
    }

    @Test
    void configEngineIgnoresUnknownDefaultPermissionMode(@TempDir Path cwd) throws IOException {
        // An unknown mode name doesn't break the load — the field
        // is just kept as-is and the engine logs a warning at
        // construction. The user's other config (workflow, matrix)
        // still applies.
        Path configFile = cwd.resolve(".aethercode").resolve("config.json");
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile,
                "{\n" +
                "  \"defaultPermissionMode\": \"MAYBE_PROMPT\",\n" +
                "  \"workflow\": \"legacy\"\n" +
                "}\n");
        AetherCodeConfig c = ConfigEngine.loadFrom(configFile);
        assertThat(c.defaultPermissionMode).isEqualTo("MAYBE_PROMPT");
        assertThat(c.workflow).isEqualTo("legacy");
    }

    @Test
    void suggesterEmptyDir_recommendsAskBeforeTool(@TempDir Path cwd) {
        // empty / exploration projects get the explicit
        // ASK_BEFORE_TOOL recommendation (was DEFAULT legacy).
        // The reason field carries the human-readable signal.
        PermissionModeSuggester.Suggestion s = PermissionModeSuggester.suggest(cwd);
        assertThat(s.mode()).isEqualTo(PermissionMode.ASK_BEFORE_TOOL);
        assertThat(s.reasons()).contains("empty directory");
    }

    @Test
    void suggesterNullRoot_recommendsAskBeforeTool() {
        PermissionModeSuggester.Suggestion s = PermissionModeSuggester.suggest(null);
        assertThat(s.mode()).isEqualTo(PermissionMode.ASK_BEFORE_TOOL);
        assertThat(s.reasons()).contains("no project root");
    }

    @Test
    void suggesterMatureSource_recommendsAcceptEdits(@TempDir Path cwd) throws IOException {
        // The "mature source project" heuristic is unchanged: a
        // project with src/ + tests/ still gets ACCEPT_EDITS so
        // the user isn't interrupted for every file write. R163
        // only changed the empty / no-recognised heuristic.
        Files.createDirectory(cwd.resolve("src"));
        Files.createDirectory(cwd.resolve("tests"));
        PermissionModeSuggester.Suggestion s = PermissionModeSuggester.suggest(cwd);
        assertThat(s.mode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
    }

    @Test
    void suggesterCiConfig_recommendsAcceptTask(@TempDir Path cwd) throws IOException {
        // Unchanged: a project with CI config still gets
        // ACCEPT_TASK. R163 only added the ASK_BEFORE_TOOL
        // recommendation for the "no signal" case.
        Files.createDirectories(cwd.resolve(".github").resolve("workflows"));
        Files.createDirectory(cwd.resolve("src"));
        Files.createDirectory(cwd.resolve("tests"));
        PermissionModeSuggester.Suggestion s = PermissionModeSuggester.suggest(cwd);
        assertThat(s.mode()).isEqualTo(PermissionMode.ACCEPT_TASK);
    }
}
