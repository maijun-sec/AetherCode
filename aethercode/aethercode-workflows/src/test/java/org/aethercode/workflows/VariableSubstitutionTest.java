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
