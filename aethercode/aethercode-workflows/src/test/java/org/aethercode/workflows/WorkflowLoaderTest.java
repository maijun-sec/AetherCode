package org.aethercode.workflows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-2-02 / Phase 2.1 acceptance: SnakeYAML → {@link Workflow}
 * (schema v1). Two tests, one for a typical happy path, one
 * for the structural-error path. Deeper coverage (enum values,
 * multi-line prompts, defaults) lives in the engine tests
 * that exercise the loader as part of the run.
 */
class WorkflowLoaderTest {

    private final WorkflowLoader loader = new WorkflowLoader();

    @Test
    void parsesFullV1Document(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("tdd-feature.yaml");
        Files.writeString(file, """
                version: 1
                name: tdd-feature
                description: Write a failing test, implement to green, refactor.
                inputs:
                  feature:
                    type: string
                    required: true
                    description: "The feature to implement, in one sentence."
                  framework:
                    type: enum
                    values: [junit, pytest]
                    default: junit
                skills:
                  - tdd
                  - code-review
                prompts:
                  - role: system
                    content: |
                      You are a TDD expert.
                  - role: user
                    content: |
                      Feature: {{inputs.feature}}
                      Working dir: {{cwd}}
                todos:
                  - "Write a failing test for {{inputs.feature}}"
                  - "Implement to green"
                limits:
                  wallClockMs: 14400000
                  tokens: 4000000
                  calls: 200
                """, StandardCharsets.UTF_8);

        Workflow wf = loader.load(file);

        assertThat(wf.version()).isEqualTo(1);
        assertThat(wf.name()).isEqualTo("tdd-feature");
        assertThat(wf.description()).contains("Write a failing test");
        assertThat(wf.inputs()).containsOnlyKeys("feature", "framework");
        assertThat(wf.inputs().get("feature").type()).isEqualTo("string");
        assertThat(wf.inputs().get("feature").required()).isTrue();
        assertThat(wf.inputs().get("framework").values()).containsExactly("junit", "pytest");
        assertThat(wf.inputs().get("framework").defaultValue()).isEqualTo("junit");
        assertThat(wf.skills()).containsExactly("tdd", "code-review");
        assertThat(wf.prompts()).hasSize(2);
        assertThat(wf.prompts().get(0).role()).isEqualTo("system");
        assertThat(wf.prompts().get(0).content()).contains("TDD expert");
        assertThat(wf.prompts().get(1).role()).isEqualTo("user");
        assertThat(wf.prompts().get(1).content()).contains("{{inputs.feature}}");
        assertThat(wf.todos()).hasSize(2);
        assertThat(wf.limits().wallClockMs()).isEqualTo(14_400_000L);
        assertThat(wf.limits().tokens()).isEqualTo(4_000_000L);
        assertThat(wf.limits().calls()).isEqualTo(200L);
    }

    @Test
    void unknownSchemaVersionFails(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("future.yaml");
        Files.writeString(file, """
                version: 99
                name: future
                description: A future schema version.
                prompts:
                  - role: user
                    content: hi
                """, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> loader.load(file))
                .isInstanceOf(WorkflowParserException.class)
                .hasMessageContaining("unsupported schema version 99");
    }
}
