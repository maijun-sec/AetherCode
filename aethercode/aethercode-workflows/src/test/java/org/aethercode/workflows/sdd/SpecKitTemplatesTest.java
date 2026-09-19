package org.aethercode.workflows.sdd;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R292 — guard test: the daemon jar must ship the 5 Spec Kit
 * templates + constitution + hard-rules as bundled
 * resources. If any of these go missing the entire SDD
 * pipeline runs in degraded mode (no prompts, no
 * governance), so we fail fast in CI rather than at
 * production runtime.
 */
class SpecKitTemplatesTest {

    @Test
    void bundled_resources_all_present() throws Exception {
        String[] required = {
                "spec-kit/templates/specify-template.md",
                "spec-kit/templates/plan-template.md",
                "spec-kit/templates/tasks-template.md",
                "spec-kit/templates/checklist-template.md",
                "spec-kit/memory/constitution-template.md",
                "spec-kit/hard-rules.md",
        };
        for (String path : required) {
            try (InputStream in = openResource(path)) {
                assertNotNull(in, "missing resource: " + path);
                byte[] bytes = in.readAllBytes();
                assertTrue(bytes.length > 50,
                        path + " is suspiciously small (" + bytes.length + " bytes)");
                String text = new String(bytes, StandardCharsets.UTF_8);
                assertFalse(text.isBlank(), path + " is blank");
            }
        }
    }

    @Test
    void spec_template_has_user_stories_section() throws Exception {
        String tpl = readResource("spec-kit/templates/specify-template.md");
        assertTrue(tpl.contains("User Scenarios & Testing"),
                "specify-template.md should contain a User Scenarios section (Spec Kit mandate)");
        assertTrue(tpl.contains("Functional Requirements"),
                "specify-template.md should contain Functional Requirements");
        assertTrue(tpl.contains("Success Criteria"),
                "specify-template.md should contain Success Criteria");
    }

    @Test
    void plan_template_has_constitution_check() throws Exception {
        String tpl = readResource("spec-kit/templates/plan-template.md");
        assertTrue(tpl.contains("Constitution Check"),
                "plan-template.md should gate on the Constitution Check section");
        assertTrue(tpl.contains("Technical Context"),
                "plan-template.md should specify Technical Context");
    }

    @Test
    void tasks_template_marks_parallel_tasks() throws Exception {
        String tpl = readResource("spec-kit/templates/tasks-template.md");
        assertTrue(tpl.contains("[P]"),
                "tasks-template.md should show the [P] parallel-task marker");
        assertTrue(tpl.contains("[US"),
                "tasks-template.md should show the [USn] story-label marker");
    }

    @Test
    void constitution_template_has_governance_section() throws Exception {
        String tpl = readResource("spec-kit/memory/constitution-template.md");
        assertTrue(tpl.contains("Governance"),
                "constitution-template.md should include a Governance section");
        assertTrue(tpl.contains("Version"),
                "constitution-template.md should track its SemVer version");
    }

    @Test
    void hard_rules_ban_tool_calls_and_think_blocks() throws Exception {
        String tpl = readResource("spec-kit/hard-rules.md");
        assertTrue(tpl.contains("Do NOT call any tools"),
                "hard-rules must ban tool calls during phases");
        assertTrue(tpl.contains("<think>") || tpl.contains("Do NOT emit <think>"),
                "hard-rules must ban <think> blocks (the wording may vary)");
        assertTrue(tpl.contains("preamble"),
                "hard-rules must ban preamble lines like 'Draft:' / 'Note:'");
    }

    private static InputStream openResource(String resource) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = SpecKitTemplatesTest.class.getClassLoader();
        return cl.getResourceAsStream(resource);
    }

    private static String readResource(String resource) throws Exception {
        try (InputStream in = openResource(resource)) {
            assertNotNull(in, "missing resource: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}