/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.aethercode.sdk;

import org.aethercode.config.Action;
import org.aethercode.config.OpKind;
import org.aethercode.config.PermissionMatrix;
import org.aethercode.config.PermissionModeSuggester;
import org.aethercode.core.permission.PermissionMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * integration test for the engine's cached
 * permission-mode suggestion. The engine reads the
 * project root, runs the suggester, and exposes the
 * result via {@link AetherCodeEngine#permissionModeSuggestion()}.
 * A config reload re-runs the suggester.
 */
class PermissionModeSuggesterEngineTest {

    private Path cwd;
    private AetherCodeEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        cwd = Files.createTempDirectory("aethercode-suggester-test");
        Files.createDirectory(cwd.resolve(".aethercode"));
        Files.writeString(cwd.resolve(".aethercode").resolve("config.json"), """
                {
                  "version": 1,
                  "permissionMatrix": {
                    "file_write": { "**": { "*": "ASK" } }
                  }
                }
                """);
    }

    @AfterEach
    void tearDown() throws Exception {
        engine = null;
        if (cwd != null && Files.exists(cwd)) {
            try {
                Files.walk(cwd)
                        .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignore) {} });
            } catch (Exception ignore) {}
        }
    }

    @Test
    void suggestion_matureProjectWithCI_isAcceptTask() throws Exception {
        // src/ + tests/ + .github/workflows/ — strongest signal.
        Files.createDirectory(cwd.resolve("src"));
        Files.createDirectory(cwd.resolve("tests"));
        Files.createDirectories(cwd.resolve(".github").resolve("workflows"));
        Files.writeString(cwd.resolve(".github").resolve("workflows")
                .resolve("ci.yml"), "name: ci");

        engine = AetherCodeEngine.builder()
                .cwd(cwd)
                .permissionMatrix(new PermissionMatrix()
                        .withOverride("file_write", "**", OpKind.CREATE, Action.ASK))
                .build();

        PermissionModeSuggester.Suggestion s = engine.permissionModeSuggestion();
        assertThat(s).isNotNull();
        assertThat(s.mode()).isEqualTo(PermissionMode.ACCEPT_TASK);
        assertThat(s.reasons()).hasSize(2);
    }

    @Test
    void suggestion_matureProjectNoCI_isAcceptEdits() throws Exception {
        // src/ + tests/ only — strong but not strongest.
        Files.createDirectory(cwd.resolve("src"));
        Files.createDirectory(cwd.resolve("tests"));

        engine = AetherCodeEngine.builder()
                .cwd(cwd)
                .permissionMatrix(new PermissionMatrix()
                        .withOverride("file_write", "**", OpKind.CREATE, Action.ASK))
                .build();

        PermissionModeSuggester.Suggestion s = engine.permissionModeSuggestion();
        assertThat(s).isNotNull();
        assertThat(s.mode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
        assertThat(s.reasons()).containsExactly("has src/ + tests/");
    }

    @Test
    void suggestion_emptyProject_isAskBeforeTool() throws Exception {
        // empty / exploration projects now get the explicit
        // ASK_BEFORE_TOOL recommendation (was DEFAULT legacy;
        // both modes are semantically identical — the new name
        // is more discoverable in the TUI's status bar).
        engine = AetherCodeEngine.builder()
                .cwd(cwd)
                .permissionMatrix(new PermissionMatrix()
                        .withOverride("file_write", "**", OpKind.CREATE, Action.ASK))
                .build();

        PermissionModeSuggester.Suggestion s = engine.permissionModeSuggestion();
        assertThat(s).isNotNull();
        assertThat(s.mode()).isEqualTo(PermissionMode.ASK_BEFORE_TOOL);
    }

    @Test
    void suggestion_isDeterministicAcrossEngines() throws Exception {
        // Two engines on the same project get the same suggestion.
        Files.createDirectory(cwd.resolve("src"));
        Files.createDirectory(cwd.resolve("tests"));
        engine = AetherCodeEngine.builder()
                .cwd(cwd)
                .permissionMatrix(new PermissionMatrix()
                        .withOverride("file_write", "**", OpKind.CREATE, Action.ASK))
                .build();
        AetherCodeEngine engine2 = AetherCodeEngine.builder()
                .cwd(cwd)
                .permissionMatrix(new PermissionMatrix()
                        .withOverride("file_write", "**", OpKind.CREATE, Action.ASK))
                .build();
        try {
            PermissionModeSuggester.Suggestion s1 = engine.permissionModeSuggestion();
            PermissionModeSuggester.Suggestion s2 = engine2.permissionModeSuggestion();
            assertThat(s1.mode()).isEqualTo(s2.mode());
            assertThat(s1.reasons()).isEqualTo(s2.reasons());
        } finally {
            // engine2 is not stored in the field; no close().
        }
    }
}
