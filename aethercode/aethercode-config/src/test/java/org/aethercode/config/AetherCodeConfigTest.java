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

package org.aethercode.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link AetherCodeConfig#defaults()} and {@link ConfigEngine#fromMap}.
 * Defaults must be safe; a fromMap with empty / partial / weird input must not throw.
 */
class AetherCodeConfigTest {

    @Test
    void defaults_haveWorkflowDesignFirst_andMatrix() {
        AetherCodeConfig c = AetherCodeConfig.defaults();
        assertThat(c.workflow).isEqualTo("design-first");
        assertThat(c.skipConfirmation).isFalse();
        assertThat(c.skipConfirmationRounds).isEqualTo(0);
        assertThat(c.version).isEqualTo(1);
        assertThat(c.permissionMatrix).isNotNull();
        // Matrix should cover at least the standard tools.
        assertThat(c.permissionMatrix.toolNames())
                .contains("file_read", "file_write", "file_edit", "bash", "web_fetch");
    }

    @Test
    void fromMap_empty_returnsDefaults() {
        AetherCodeConfig c = ConfigEngine.fromMap(Map.of());
        assertThat(c.workflow).isEqualTo("design-first");
        assertThat(c.permissionMatrix.toolNames()).isNotEmpty();
    }

    @Test
    void fromMap_null_returnsDefaults() {
        AetherCodeConfig c = ConfigEngine.fromMap(null);
        assertThat(c.workflow).isEqualTo("design-first");
    }

    @Test
    void fromMap_workflowOverride_isHonored() {
        AetherCodeConfig c = ConfigEngine.fromMap(Map.of("workflow", "legacy"));
        assertThat(c.workflow).isEqualTo("legacy");
    }

    @Test
    void fromMap_skipConfirmationFlag_isHonored() {
        AetherCodeConfig c = ConfigEngine.fromMap(Map.of("skipConfirmation", true));
        assertThat(c.skipConfirmation).isTrue();
    }

    @Test
    void fromMap_skipConfirmationRounds_isNormalizedToNonNegative() {
        AetherCodeConfig c = ConfigEngine.fromMap(Map.of("skipConfirmationRounds", -7));
        assertThat(c.skipConfirmationRounds).isEqualTo(0);
    }

    @Test
    void fromMap_partialMatrix_preservesOtherFields() {
        AetherCodeConfig c = ConfigEngine.fromMap(Map.of(
                "workflow", "design-first",
                "permissionMatrix", Map.of(
                        "file_write", Map.of(
                                "src/main/**", Map.of("CREATE", "ALLOW")
                        )
                )
        ));
        // partial matrix should fully replace the default; verify the
        // project override took effect.
        Action a = c.permissionMatrix.lookup("file_write", "src/main/java/Foo.java", OpKind.CREATE);
        assertThat(a).isEqualTo(Action.ALLOW);
        // other tools fall back to ASK (no entry in the partial matrix)
        assertThat(c.permissionMatrix.lookup("bash", null, OpKind.EXEC))
                .isEqualTo(Action.ASK);
    }

    @Test
    void fromMap_unknownFields_areIgnored() {
        AetherCodeConfig c = ConfigEngine.fromMap(Map.of(
                "version", 1,
                "unknownField", "ignored",
                "anotherOne", 42
        ));
        assertThat(c.version).isEqualTo(1);
    }
}
