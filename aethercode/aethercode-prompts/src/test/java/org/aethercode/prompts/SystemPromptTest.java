package org.aethercode.prompts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tests for the new {@code rules} section on {@link SystemPrompt.Builder}.
 *
 * <p>The behaviour is intentionally narrow: rules must be inserted between
 * identity and environment so the model sees user policy first, and an empty
 * rules value must not contribute anything to the rendered prompt (no orphan
 * blank lines, no header). Identity / environment / tooling / workflow /
 * memory / planMode ordering for non-rules fields is unchanged from the
 * existing implementation.
 */
class SystemPromptTest {

    @Test
    void rules_defaultIsEmpty_andRenderOmitsSection() {
        SystemPrompt p = SystemPrompt.builder().build();
        assertThat(p.rules()).isEmpty();
        // No "# Project rules" or "# Global rules" header when no rules are set
        assertThat(p.render()).doesNotContain("# Project rules").doesNotContain("# Global rules");
    }

    @Test
    void rules_nullClearsSection() {
        SystemPrompt p = SystemPrompt.builder().rules("hello").rules(null).build();
        assertThat(p.rules()).isEmpty();
    }

    @Test
    void rules_isRenderedAfterIdentity_andBeforeEnvironment() {
        String rendered = SystemPrompt.builder()
                .identity("IDENTITY-SENTINEL")
                .environment("ENV-SENTINEL")
                .tooling("TOOLING-SENTINEL")
                .workflow("WORKFLOW-SENTINEL")
                .rules("RULES-SENTINEL")
                .build()
                .render();
        int i = rendered.indexOf("IDENTITY-SENTINEL");
        int r = rendered.indexOf("RULES-SENTINEL");
        int e = rendered.indexOf("ENV-SENTINEL");
        int t = rendered.indexOf("TOOLING-SENTINEL");
        int w = rendered.indexOf("WORKFLOW-SENTINEL");
        assertThat(i).isGreaterThanOrEqualTo(0);
        assertThat(r).isGreaterThan(i);
        assertThat(e).isGreaterThan(r);
        assertThat(t).isGreaterThan(e);
        assertThat(w).isGreaterThan(t);
    }

    @Test
    void rules_isRenderedBeforeMemory() {
        String rendered = SystemPrompt.builder()
                .identity("I")
                .memory("MEM-SENTINEL")
                .rules("RULES-SENTINEL")
                .build()
                .render();
        assertThat(rendered.indexOf("RULES-SENTINEL")).isLessThan(rendered.indexOf("MEM-SENTINEL"));
    }

    @Test
    void rules_blankValue_doesNotAppearInRender() {
        String rendered = SystemPrompt.builder()
                .identity("I")
                .rules("   \n  \n   ")
                .build()
                .render();
        assertThat(rendered).contains("I").doesNotContain("###");
    }

    @Test
    void rules_multipleValues_lastOneWins() {
        SystemPrompt p = SystemPrompt.builder()
                .rules("first")
                .rules("second")
                .build();
        assertThat(p.rules()).isEqualTo("second");
    }

    @Test
    void rules_doesNotDisturbIdentityAccessor() {
        SystemPrompt p = SystemPrompt.builder().rules("r").build();
        // identity still default (starts with "You are AetherCode")
        assertThat(p.render()).contains("You are AetherCode").contains("r");
    }

    // pin the tool-call format guidance in the
    // default workflow. The user's recurring bug was
    // the model emitting `input: {}` (empty JSON
    // object) which the engine silently accepted and
    // ran with no params, producing "X is required"
    // errors. The fix is twofold: (a) SpringAI's
    // parseJsonArgs now logs the empty-args case
    // (SpringAiChatClient.java prior round) so a fresh
    // daemon surfaces the failure, and (b) the system
    // prompt has a "Tool call format" section near
    // the top of defaultWorkflow so the model sees
    // the JSON `tool_use` shape + a worked example +
    // an anti-example BEFORE any tool usage
    // discussion.
    @Test
    void workflow_defaultContainsToolCallFormatGuide() {
        // assert the default workflow carries the
        // tool-call format guide. We render and look
        // for the markers; the *ordering* test
        // (workflow_toolCallFormatIsBeforeThePlanPhase)
        // checks the position separately.
        String rendered = SystemPrompt.builder().build().render();
        // Section header
        assertThat(rendered).contains("Tool call format (R242-A)");
        // Worked example must show a populated input
        assertThat(rendered).contains("\"input\":{\"command\":\"ls -la /tmp/abc\"");
        // Anti-example must show the empty-input failure mode
        assertThat(rendered).contains("\"input\":{}");
        // Must tell the model NOT to use XML wrappers
        // (the "Do NOT" and "wrap" may be on different
        // lines after Java text-block auto-formatting,
        // so we assert on the key phrases).
        assertThat(rendered).containsIgnoringCase("wrap the tool_use in XML");
        assertThat(rendered).containsIgnoringCase("`<parameter>` tags");
    }

    @Test
    void workflow_toolCallFormatIsBeforeThePlanPhase() {
        // "Tool call format" must appear BEFORE
        // "Phase 1 — Plan" inside the workflow text
        // itself. We can't simply use `rendered.indexOf`
        // because the rendered text also contains
        // "Phase 1 — Plan" earlier, in the
        // defaultDesignFirst section's
        // "REPLACES the 'Phase 1 — Plan' step" line
        // (which appears in the rendered output
        // before the workflow section). So we read
        // the defaultWorkflow() string via reflection
        // (it's private static) and check positions
        // within it.
        String workflow;
        try {
            java.lang.reflect.Method m = SystemPrompt.class
                    .getDeclaredMethod("defaultWorkflow");
            m.setAccessible(true);
            workflow = (String) m.invoke(null);
        } catch (Exception e) {
            throw new AssertionError("defaultWorkflow reflection failed", e);
        }
        int fmtIdx = workflow.indexOf("Tool call format (R242-A)");
        int planIdx = workflow.indexOf("Phase 1 — Plan");
        assertThat(fmtIdx).as("Tool call format guide must be present in defaultWorkflow").isGreaterThanOrEqualTo(0);
        assertThat(planIdx).as("Phase 1 — Plan phase must be present in defaultWorkflow").isGreaterThanOrEqualTo(0);
        assertThat(fmtIdx).as("Tool call format guide must come before the Phase 1 — Plan phase")
                .isLessThan(planIdx);
    }
}
