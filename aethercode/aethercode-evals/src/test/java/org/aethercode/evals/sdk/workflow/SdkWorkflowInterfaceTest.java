package org.aethercode.evals.sdk.workflow;

import org.aethercode.workflows.Workflow;
import org.aethercode.workflows.Workflow.InputDef;
import org.aethercode.workflows.Workflow.Limits;
import org.aethercode.workflows.Workflow.PromptDef;
import org.aethercode.workflows.WorkflowValidator;
import org.aethercode.workflows.WorkflowValidator.SkillResolver;
import org.aethercode.workflows.ValidationError;
import org.aethercode.workflows.VariableSubstitution;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-sdk-9: AetherCode WorkflowEngine Interface conformance.
 *
 * <p>The {@code aethercode-workflows} module drives the TUI's
 * "click a workflow, see progress" surface. Its 16 classes (loader,
 * validator, engine, paths, variable substitution, skill composer,
 * workflow service) had <b>zero</b> interface tests before R-sdk-9;
 * R-AUDIT-SELF-IMPROVEMENT flagged this as Tier-1 risk because a
 * silent schema-version drift or substitution bug would break every
 * user-saved workflow on next launch.</p>
 *
 * <p>Scope: the loader-output invariants + substitution + validator
 * behaviour. The full {@code WorkflowEngine.run} path needs the
 * supervisor process and is out of scope for this round (covered by
 * {@code aethercode-workflows} own unit tests).</p>
 */
class SdkWorkflowInterfaceTest {

    /* ---------------- Workflow record + InputDef ---------------- */

    @Test
    void workflowRejectsNullName() {
        assertThrows(NullPointerException.class, () ->
                new Workflow(1, null, "desc", Map.of(), List.of(), List.of(),
                        List.of(), Limits.empty(), Map.of()));
    }

    @Test
    void workflowRejectsNullDescription() {
        assertThrows(NullPointerException.class, () ->
                new Workflow(1, "my-workflow", null, Map.of(), List.of(), List.of(),
                        List.of(), Limits.empty(), Map.of()));
    }

    @Test
    void workflowNormalisesNullCollectionsToEmpty() {
        Workflow w = new Workflow(1, "x", "d",
                null, null, null, null, null, null);
        assertEquals(0, w.inputs().size());
        assertEquals(0, w.skills().size());
        assertEquals(0, w.prompts().size());
        assertEquals(0, w.todos().size());
        assertTrue(w.limits().isEmpty());
    }

    @Test
    void workflowInputDefRejectsBlankType() {
        assertThrows(IllegalArgumentException.class, () ->
                new InputDef("", true, "desc", List.of(), null));
    }

    @Test
    void workflowPromptDefRejectsBlankRole() {
        assertThrows(IllegalArgumentException.class, () ->
                new PromptDef("", "content"));
    }

    @Test
    void workflowCurrentVersionIsStable() {
        // The schema version is part of the wire format; if we
        // bump it, every saved workflow must be migrated.
        assertEquals(1, Workflow.CURRENT_VERSION);
    }

    /* ---------------- withLimits + firstSystemPrompt + userPrompts ---------------- */

    @Test
    void workflowWithLimitsReplaces() {
        Workflow base = new Workflow(1, "x", "d", Map.of(), List.of(),
                List.of(), List.of(), Limits.empty(), Map.of());
        Limits newLimits = new Limits(60_000L, 1_000L, 100L, 50L, 0L);
        Workflow overridden = base.withLimits(newLimits);
        assertEquals(60_000L, overridden.limits().wallClockMs());
        assertEquals(1_000L, overridden.limits().tokens());
        // Original is unchanged (records are immutable).
        assertTrue(base.limits().isEmpty());
    }

    @Test
    void workflowFirstSystemPromptReturnsFirstSystem() {
        Workflow w = new Workflow(1, "x", "d", Map.of(), List.of(),
                List.of(
                        new PromptDef("system", "you are X"),
                        new PromptDef("user", "hi"),
                        new PromptDef("system", "you are Y")
                ), List.of(), Limits.empty(), Map.of());
        PromptDef first = w.firstSystemPrompt();
        assertNotNull(first);
        assertEquals("you are X", first.content());
    }

    @Test
    void workflowUserPromptsExcludesSystem() {
        Workflow w = new Workflow(1, "x", "d", Map.of(), List.of(),
                List.of(
                        new PromptDef("system", "sys"),
                        new PromptDef("user", "u1"),
                        new PromptDef("user", "u2"),
                        new PromptDef("developer", "dev")
                ), List.of(), Limits.empty(), Map.of());
        List<PromptDef> users = w.userPrompts();
        assertEquals(3, users.size(), "system excluded; user + developer kept");
    }

    /* ---------------- VariableSubstitution ---------------- */

    @Test
    void variableSubstitutionReplacesInputsPlaceholder() {
        VariableSubstitution sub = new VariableSubstitution(
                Map.of("name", "Alice"), "/tmp");
        assertEquals("hello Alice", sub.substitute("hello {{inputs.name}}"));
    }

    @Test
    void variableSubstitutionReplacesCwd() {
        VariableSubstitution sub = new VariableSubstitution(Map.of(), "/home/user");
        assertEquals("cwd=/home/user", sub.substitute("cwd={{cwd}}"));
    }

    @Test
    void variableSubstitutionReplacesDateAndOs() {
        VariableSubstitution sub = new VariableSubstitution(Map.of(), "/tmp");
        String result = sub.substitute("date={{date}} os={{os}}");
        assertTrue(result.startsWith("date="));
        assertTrue(result.contains("os="));
        // Neither should still contain a placeholder.
        assertFalse(result.contains("{{"));
    }

    @Test
    void variableSubstitutionRejectsUnknownPlaceholders() {
        // Unknown placeholders throw a WorkflowParserException so
        // the user immediately sees the typo rather than having
        // a literal {{unknown}} shipped to the LLM.
        VariableSubstitution sub = new VariableSubstitution(
                Map.of("name", "Alice"), "/tmp");
        assertThrows(org.aethercode.workflows.WorkflowParserException.class,
                () -> sub.substitute("hi {{unknown}}"));
    }

    @Test
    void variableSubstitutionReferencedPlaceholdersList() {
        VariableSubstitution sub = new VariableSubstitution(Map.of(), "/tmp");
        var refs = sub.referencedPlaceholders("{{inputs.a}} and {{cwd}}");
        assertTrue(refs.contains("inputs.a"));
        assertTrue(refs.contains("cwd"));
    }

    /* ---------------- WorkflowValidator ---------------- */

    @Test
    void validatorAcceptsValidWorkflow() {
        WorkflowValidator v = new WorkflowValidator();
        Workflow w = new Workflow(1, "my-workflow", "ok", Map.of(), List.of(),
                List.of(new PromptDef("system", "you are X")),
                List.of(), Limits.empty(), Map.of());
        assertTrue(v.isValid(w));
    }

    @Test
    void validatorRejectsWrongVersion() {
        WorkflowValidator v = new WorkflowValidator();
        Workflow w = new Workflow(99, "x", "d", Map.of(), List.of(),
                List.of(), List.of(), Limits.empty(), Map.of());
        List<ValidationError> errs = v.validate(w);
        assertFalse(errs.isEmpty());
        assertTrue(errs.stream().anyMatch(e -> e.message().contains("version")),
                "version error reported: " + errs);
    }

    @Test
    void validatorRejectsNonKebabName() {
        WorkflowValidator v = new WorkflowValidator();
        Workflow w = new Workflow(1, "NotKebab", "d", Map.of(), List.of(),
                List.of(), List.of(), Limits.empty(), Map.of());
        List<ValidationError> errs = v.validate(w);
        assertTrue(errs.stream().anyMatch(e -> e.message().contains("kebab")),
                "kebab-case error reported: " + errs);
    }

    @Test
    void validatorRejectsUnknownRole() {
        WorkflowValidator v = new WorkflowValidator();
        Workflow w = new Workflow(1, "x", "d", Map.of(), List.of(),
                List.of(new PromptDef("alien", "content")),
                List.of(), Limits.empty(), Map.of());
        List<ValidationError> errs = v.validate(w);
        assertTrue(errs.stream().anyMatch(e -> e.message().contains("role")),
                "role error reported: " + errs);
    }

    @Test
    void validatorKnownRolesIncludesSystemAndUser() {
        assertTrue(WorkflowValidator.KNOWN_ROLES.contains("system"));
        assertTrue(WorkflowValidator.KNOWN_ROLES.contains("user"));
        assertTrue(WorkflowValidator.KNOWN_ROLES.contains("assistant"));
        assertTrue(WorkflowValidator.KNOWN_ROLES.contains("developer"));
    }

    @Test
    void validatorKnownInputTypesIncludesCorePrimitives() {
        assertTrue(WorkflowValidator.KNOWN_INPUT_TYPES.contains("string"));
        assertTrue(WorkflowValidator.KNOWN_INPUT_TYPES.contains("number"));
        assertTrue(WorkflowValidator.KNOWN_INPUT_TYPES.contains("enum"));
    }

    @Test
    void validatorWithSkillResolverCanMatchSkills() {
        // The default skill resolver matches nothing; with a
        // custom resolver that matches "ok-skill", the validator
        // doesn't warn about the missing skill.
        SkillResolver resolver = name -> name.equals("ok-skill");
        WorkflowValidator v = new WorkflowValidator(resolver);
        Workflow w = new Workflow(1, "x", "d", Map.of(),
                List.of("ok-skill"),
                List.of(new PromptDef("system", "sys")),
                List.of(), Limits.empty(), Map.of());
        assertTrue(v.isValid(w));
    }

    /* ---------------- Limits.toMap ---------------- */

    @Test
    void limitsToMapOmitsNullFields() {
        Limits l = new Limits(60_000L, null, 100L, null, null);
        Map<String, Object> m = l.toMap();
        assertEquals(60_000L, m.get("wallClockMs"));
        assertEquals(100L, m.get("calls"));
        assertFalse(m.containsKey("tokens"));
        assertFalse(m.containsKey("fileWrites"));
        assertFalse(m.containsKey("network"));
    }

    @Test
    void limitsEmptyHasZeroEntries() {
        assertTrue(Limits.empty().isEmpty());
        assertEquals(0, Limits.empty().toMap().size());
    }
}
