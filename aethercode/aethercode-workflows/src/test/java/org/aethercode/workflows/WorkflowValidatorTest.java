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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T-2-03 / Phase 2.1 acceptance: {@link WorkflowValidator}
 * reports every problem with a workflow in a single pass.
 * Six tests cover the most common authoring mistakes.
 */
class WorkflowValidatorTest {

    private final WorkflowValidator validator = new WorkflowValidator();

    @Test
    void cleanWorkflowProducesNoErrors() {
        Workflow wf = new Workflow(
                1, "tdd-feature", "Write a failing test, implement to green.",
                Map.of("feature", new Workflow.InputDef("string", true, "the feature",
                        List.of(), null)),
                List.of("tdd"),
                List.of(new Workflow.PromptDef("system", "be terse"),
                        new Workflow.PromptDef("user", "{{inputs.feature}}")),
                List.of("step 1"),
                Workflow.Limits.empty(),
                Map.of());
        assertThat(validator.validate(wf)).isEmpty();
    }

    @Test
    void nameMustBeKebabCase() {
        Workflow wf = new Workflow(1, "TDD_Feature", "", Map.of(), List.of(),
                List.of(new Workflow.PromptDef("user", "x")),
                List.of(), Workflow.Limits.empty(), Map.of());
        List<ValidationError> errs = validator.validate(wf);
        assertThat(errs).extracting(ValidationError::path)
                .contains("name");
        assertThat(errs.toString()).contains("kebab-case");
    }

    @Test
    void nonKebabCaseInputNameIsReported() {
        // Workflow.InputDef with a non-kebab-case name → the
        // validator must flag it. (The record's copyOf() prevents
        // literal duplicate keys from reaching the validator,
        // so we test the kebab-case constraint instead.)
        Workflow.InputDef def = new Workflow.InputDef("string", true, "x", List.of(), null);
        Workflow wf = new Workflow(1, "v", "", Map.of("BadName", def), List.of(),
                List.of(new Workflow.PromptDef("user", "x")),
                List.of(), Workflow.Limits.empty(), Map.of());
        assertThat(validator.validate(wf))
                .anyMatch(e -> e.path().equals("inputs.BadName") && e.message().contains("kebab-case"));
    }

    @Test
    void enumInputsMustListTheirValues() {
        Workflow.InputDef bad = new Workflow.InputDef("enum", true, "x", List.of(), null);
        Workflow wf = new Workflow(1, "e", "", Map.of("color", bad), List.of(),
                List.of(new Workflow.PromptDef("user", "x")),
                List.of(), Workflow.Limits.empty(), Map.of());
        assertThat(validator.validate(wf))
                .anyMatch(e -> e.path().equals("inputs.color.values"));
    }

    @Test
    void blankPromptContentIsAnError() {
        Workflow wf = new Workflow(1, "p", "", Map.of(), List.of(),
                List.of(new Workflow.PromptDef("user", "  ")),
                List.of(), Workflow.Limits.empty(), Map.of());
        assertThat(validator.validate(wf))
                .anyMatch(e -> e.path().equals("prompts[0].content"));
    }

    @Test
    void negativeLimitsAreRejected() {
        Workflow.Limits bad = new Workflow.Limits(-1L, 0L, 200L, 5000L, null);
        Workflow wf = new Workflow(1, "lim", "", Map.of(), List.of(),
                List.of(new Workflow.PromptDef("user", "x")),
                List.of(), bad, Map.of());
        List<ValidationError> errs = validator.validate(wf);
        assertThat(errs).extracting(ValidationError::path)
                .contains("limits.wallClockMs", "limits.tokens");
        assertThat(errs).noneMatch(e -> e.path().equals("limits.calls"));
    }
}
