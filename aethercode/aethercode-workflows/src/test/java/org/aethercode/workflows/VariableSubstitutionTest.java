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

package org.aethercode.workflows;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-2-05 / Phase 2.1 acceptance: {@code {{inputs.X}}}, {@code {{cwd}}},
 * {@code {{date}}}, {@code {{os}}}. Four tests cover each
 * placeholder family and the error path.
 */
class VariableSubstitutionTest {

    @Test
    void inputsAndCwdAreSubstituted() {
        VariableSubstitution sub = new VariableSubstitution(
                Map.of("feature", "login flow", "framework", "junit"),
                "/home/me/proj", "2026-08-29", "linux");
        String out = sub.substitute("Feature: {{inputs.feature}} ({{inputs.framework}}) in {{cwd}}");
        assertThat(out).isEqualTo("Feature: login flow (junit) in /home/me/proj");
    }

    @Test
    void dateAndOsPlaceholders() {
        VariableSubstitution sub = new VariableSubstitution(
                Map.of(), "/p", "2026-01-15", "windows");
        String out = sub.substitute("today={{date}} os={{os}}");
        assertThat(out).isEqualTo("today=2026-01-15 os=windows");
    }

    @Test
    void undefinedInputThrowsWithHelpfulMessage() {
        VariableSubstitution sub = new VariableSubstitution(Map.of(), "/p");
        assertThatThrownBy(() -> sub.substitute("{{inputs.missing}}"))
                .isInstanceOf(WorkflowParserException.class)
                .hasMessageContaining("undefined input 'missing'");
    }

    @Test
    void unknownPlaceholderThrowsWithListOfSupportedNames() {
        VariableSubstitution sub = new VariableSubstitution(Map.of(), "/p");
        assertThatThrownBy(() -> sub.substitute("{{evil}}"))
                .isInstanceOf(WorkflowParserException.class)
                .hasMessageContaining("unknown placeholder '{{evil}}'")
                .hasMessageContaining("inputs.<name>")
                .hasMessageContaining("cwd")
                .hasMessageContaining("date")
                .hasMessageContaining("os");
    }
}
