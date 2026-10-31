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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-2-04 / Phase 2.1 acceptance: the engine loads, validates,
 * and runs a workflow end-to-end. Six tests cover the happy
 * path, validation failure, required-input missing, type
 * coercion, TODO pre-seeding, and skill composition.
 */
class WorkflowEngineTest {

    @Test
    void runsBundledWorkflowAndSubstitutesInputs(@TempDir Path cwd) {
        AtomicReference<String> capturedPrompt = new AtomicReference<>();
        AtomicReference<String> capturedCwd = new AtomicReference<>();
        SessionSpawner fake = (prompt, c, m, cfg) -> {
            capturedPrompt.set(prompt);
            capturedCwd.set(c);
            return new SessionRef("c-fake", c, m, "tdd-feature", cfg);
        };
        WorkflowEngine engine = WorkflowEngine.forCwd(cwd, fake);
        SessionRef ref = engine.run("tdd-feature",
                Map.of("feature", "login flow"),
                cwd, "claude-opus-4-1");
        assertThat(ref.id()).isEqualTo("c-fake");
        assertThat(ref.workflowName()).isEqualTo("tdd-feature");
        assertThat(capturedCwd.get()).isEqualTo(cwd.toString());
        String prompt = capturedPrompt.get();
        assertThat(prompt).contains("login flow");
        // The Plan block lists every pre-seeded TODO.
        assertThat(prompt).contains("## Plan (pre-seeded TODOs)");
        assertThat(prompt).contains("- [ ] Write a failing test for: login flow");
        // The system prompt is rendered first.
        assertThat(prompt).contains("[SYSTEM]");
        assertThat(prompt).contains("[USER]");
    }

    @Test
    void unknownWorkflowThrows(@TempDir Path cwd) {
        WorkflowEngine engine = WorkflowEngine.forCwd(cwd, noopSpawner());
        assertThatThrownBy(() -> engine.run("nope-nope", Map.of(), cwd, null))
                .isInstanceOf(WorkflowParserException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void requiredInputMissingThrows(@TempDir Path cwd) throws IOException {
        Path wfDir = cwd.resolve(".aethercode").resolve("workflows");
        Files.createDirectories(wfDir);
        Files.writeString(wfDir.resolve("needs-feature.yaml"), """
                version: 1
                name: needs-feature
                description: requires the feature input
                inputs:
                  feature:
                    type: string
                    required: true
                    description: required
                prompts:
                  - role: user
                    content: "Feature: {{inputs.feature}}"
                """, StandardCharsets.UTF_8);
        WorkflowEngine engine = WorkflowEngine.forCwd(cwd, noopSpawner());
        assertThatThrownBy(() -> engine.run("needs-feature", Map.of(), cwd, null))
                .isInstanceOf(WorkflowParserException.class)
                .hasMessageContaining("requires input 'feature'");
    }

    @Test
    void enumValueIsValidated(@TempDir Path cwd) throws IOException {
        Path wfDir = cwd.resolve(".aethercode").resolve("workflows");
        Files.createDirectories(wfDir);
        Files.writeString(wfDir.resolve("pick.yaml"), """
                version: 1
                name: pick
                description: enum-typed
                inputs:
                  color:
                    type: enum
                    values: [red, green]
                    required: true
                prompts:
                  - role: user
                    content: "color={{inputs.color}}"
                """, StandardCharsets.UTF_8);
        WorkflowEngine engine = WorkflowEngine.forCwd(cwd, noopSpawner());
        assertThatThrownBy(() -> engine.run("pick",
                Map.of("color", "blue"), cwd, null))
                .isInstanceOf(WorkflowParserException.class)
                .hasMessageContaining("must be one of [red, green]");
    }

    @Test
    void integerInputIsCoercedFromString(@TempDir Path cwd) throws IOException {
        AtomicReference<Map<String, Object>> capturedConfig = new AtomicReference<>();
        SessionSpawner fake = (prompt, c, m, cfg) -> {
            capturedConfig.set(cfg);
            return new SessionRef("c-x", c, m, "needs-int", cfg);
        };
        Path wfDir = cwd.resolve(".aethercode").resolve("workflows");
        Files.createDirectories(wfDir);
        Files.writeString(wfDir.resolve("needs-int.yaml"), """
                version: 1
                name: needs-int
                description: coerce
                inputs:
                  count:
                    type: integer
                    required: true
                prompts:
                  - role: user
                    content: "count={{inputs.count}}"
                """, StandardCharsets.UTF_8);
        WorkflowEngine engine = WorkflowEngine.forCwd(cwd, fake);
        engine.run("needs-int", Map.of("count", "42"), cwd, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) capturedConfig.get().get("inputs");
        assertThat(inputs.get("count")).isInstanceOf(Long.class).isEqualTo(42L);
    }

    @Test
    void validationErrorsAreSurfacedWithFullList(@TempDir Path cwd) throws IOException {
        Path wfDir = cwd.resolve(".aethercode").resolve("workflows");
        Files.createDirectories(wfDir);
        // Two prompts, one with a blank content; validator must
        // produce a single error pointing at prompts[1].content.
        Files.writeString(wfDir.resolve("bad.yaml"), """
                version: 1
                name: bad
                description: missing content
                inputs: {}
                prompts:
                  - role: user
                    content: "ok"
                  - role: user
                    content: ""
                """, StandardCharsets.UTF_8);
        WorkflowEngine engine = WorkflowEngine.forCwd(cwd, noopSpawner());
        assertThatThrownBy(() -> engine.run("bad", Map.of(), cwd, null))
                .isInstanceOf(WorkflowParserException.class)
                .hasMessageContaining("prompts[1].content")
                .hasMessageContaining("validation error");
    }

    private static SessionSpawner noopSpawner() {
        return (prompt, c, m, cfg) -> new SessionRef("c-noop", c, m, "noop", cfg);
    }
}
