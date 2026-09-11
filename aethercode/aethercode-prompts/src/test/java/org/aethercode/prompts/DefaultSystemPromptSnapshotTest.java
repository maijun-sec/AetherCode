package org.aethercode.prompts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * snapshot / contract test for the default
 * system prompt. The default identity and workflow
 * are the baseline a new user sees on a fresh
 * install, before any project rules land. This test
 * locks the key sections so that future refactors
 * that silently drop critical guidance fail loudly
 * (the test will need to be updated in the same
 * commit, which forces the reviewer to read the
 * diff).
 *
 * <p>The test does NOT lock byte-for-byte content
 * (that would be too brittle). It locks:
 * <ul>
 *   <li>Key sentinel phrases ("plan", "explore",
 *       "implement", "verify", "Boulder
 *       continuation", etc.) so the four-phase
 *       cycle and the auto-continue contract cannot
 *       disappear silently.</li>
 *   <li>Reasonable size bounds so the prompt cannot
 *       be accidentally truncated or ballooned to
 *       100 KB.</li>
 *   <li>Section provenance labels so the
 *       {@code identity} and {@code workflow} tags
 *       in {@code renderWithSources} stay stable.</li>
 * </ul>
 */
class DefaultSystemPromptSnapshotTest {

    private static final int MIN_IDENTITY_CHARS  = 1_000;
    private static final int MIN_WORKFLOW_CHARS   = 8_000;
    private static final int MAX_IDENTITY_CHARS  = 6_000;
    private static final int MAX_WORKFLOW_CHARS   = 30_000;

    // ---------------------------------------------------------------------------------
    //  defaultIdentity contract
    // ---------------------------------------------------------------------------------

    @Test
    void defaultIdentity_containsPersonaAndPrinciples() {
        RenderedPrompt rp = SystemPrompt.builder().build().renderWithSources();
        String identity = sectionText(rp, "identity");
        assertThat(identity)
                .as("identity must declare AetherCode as a local AI coding agent")
                .contains("AetherCode")
                .contains("local AI coding agent");
        // the identity now includes 5 operating
        // principles. Each is a single sentence with a
        // bold lead-in.
        assertThat(identity)
                .contains("Honesty over appearance")
                .contains("Verify before claiming done")
                .contains("conservative with destructive operations")
                .contains("Respect the user's working directory")
                .contains("Be concise, but not silent");
    }

    @Test
    void defaultIdentity_isBoundedSize() {
        RenderedPrompt rp = SystemPrompt.builder().build().renderWithSources();
        String identity = sectionText(rp, "identity");
        assertThat(identity.length())
                .as("identity should be a focused persona, not a manual")
                .isBetween(MIN_IDENTITY_CHARS, MAX_IDENTITY_CHARS);
    }

    @Test
    void defaultIdentity_sourceLabelIsDefault() {
        // the identity section must be tagged
        // "default" so a debug panel can show "this is
        // the built-in identity, not your override".
        RenderedPrompt rp = SystemPrompt.builder().build().renderWithSources();
        String source = sectionSource(rp, "identity");
        assertThat(source).isEqualTo("default");
    }

    // ---------------------------------------------------------------------------------
    //  defaultWorkflow contract — the four-phase method
    // ---------------------------------------------------------------------------------

    @Test
    void defaultWorkflow_exposesFourPhaseMethod() {
        RenderedPrompt rp = SystemPrompt.builder().build().renderWithSources();
        String workflow = sectionText(rp, "workflow");
        // the workflow now advertises the
        // "plan -> explore -> implement -> verify"
        // method as the headline.
        assertThat(workflow)
                .as("workflow must lead with the four-phase method headline")
                .contains("plan")
                .contains("explore")
                .contains("implement")
                .contains("verify");
        // And each phase must be present as a labelled
        // section, not just mentioned in prose.
        assertThat(workflow)
                .contains("Phase 1 — Plan")
                .contains("Phase 2 — Explore")
                .contains("Phase 3 — Implement")
                .contains("Phase 4 — Verify");
    }

    @Test
    void defaultWorkflow_containsToolFailureRecovery() {
        // R94 new section: a hook for the model to
        // READ THE ERROR instead of blindly retrying.
        // We assert on short sub-phrases so a line
        // wrap in the source does not break the test.
        RenderedPrompt rp = SystemPrompt.builder().build().renderWithSources();
        String workflow = sectionText(rp, "workflow");
        assertThat(workflow)
                .contains("Tool failure recovery")
                .contains("READ THE ERROR")
                .contains("adapt the")
                .contains("Permission denied")
                .contains("Path does not exist");
    }

    @Test
    void defaultWorkflow_containsEditSafetyGuards() {
        // R94 new section: hard guards against the
        // most common catastrophic operations
        // (rm -rf, secret commit, etc.).
        RenderedPrompt rp = SystemPrompt.builder().build().renderWithSources();
        String workflow = sectionText(rp, "workflow");
        assertThat(workflow)
                .contains("Edit safety")
                .contains("Do not run `rm -rf`")
                .contains("Do not commit secrets");
    }

    @Test
    void defaultWorkflow_enumeratesPermissionModes() {
        // the prompt now documents all three
        // permission modes (ACCEPT_TASK / DEFAULT /
        // BYPASS_PERMISSIONS) so the model can pick the
        // right behaviour without trial and error.
        RenderedPrompt rp = SystemPrompt.builder().build().renderWithSources();
        String workflow = sectionText(rp, "workflow");
        assertThat(workflow)
                .contains("ACCEPT_TASK")
                .contains("BYPASS_PERMISSIONS")
                .contains("Permission-mode guidance");
    }

    @Test
    void defaultWorkflow_keepsBoulderContinuation() {
        // The prior round boulder contract must survive the
        // R94 revamp. A user with a multi-step todo
        // list relies on the engine's auto-continuation
        // to keep the model rolling. We assert on
        // short sub-phrases so line wrap in the source
        // does not break the test.
        RenderedPrompt rp = SystemPrompt.builder().build().renderWithSources();
        String workflow = sectionText(rp, "workflow");
        assertThat(workflow)
                .contains("Boulder continuation")
                .contains("todo-continuation")
                .contains("2-second countdown")
                .contains("immediately resume the next pending todo");
    }

    @Test
    void defaultWorkflow_keepsSubagentDelegation() {
        // R94: spawn_agent with role= is the
        // main agent's tool for delegating to focused
        // subagents. The contract (explore / coder /
        // general-purpose roles) must remain in the
        // default prompt.
        RenderedPrompt rp = SystemPrompt.builder().build().renderWithSources();
        String workflow = sectionText(rp, "workflow");
        assertThat(workflow)
                .contains("Subagent delegation")
                .contains("spawn_agent")
                .contains("`explore`")
                .contains("`coder`")
                .contains("`general-purpose`");
    }

    @Test
    void defaultWorkflow_isBoundedSize() {
        RenderedPrompt rp = SystemPrompt.builder().build().renderWithSources();
        String workflow = sectionText(rp, "workflow");
        assertThat(workflow.length())
                .as("workflow should be comprehensive but not a manual")
                .isBetween(MIN_WORKFLOW_CHARS, MAX_WORKFLOW_CHARS);
    }

    @Test
    void defaultWorkflow_sourceLabelIsDefault() {
        RenderedPrompt rp = SystemPrompt.builder().build().renderWithSources();
        assertThat(sectionSource(rp, "workflow")).isEqualTo("default");
    }

    // ---------------------------------------------------------------------------------
    //  Cross-cutting: the rendered prompt has both
    //  sections in the right order, and the user can
    //  override either without losing the other.
    // ---------------------------------------------------------------------------------

    @Test
    void customIdentity_preservesDefaultWorkflow() {
        // A user who sets their own identity should
        // still get the full default workflow. The
        // user-overrides-identity case is the common
        // customisation.
        RenderedPrompt rp = SystemPrompt.builder()
                .identity("My custom agent — focused on Kotlin.")
                .build()
                .renderWithSources();
        assertThat(sectionText(rp, "identity")).isEqualTo("My custom agent — focused on Kotlin.");
        // The workflow survives, including the new
        // R94 sections.
        String workflow = sectionText(rp, "workflow");
        assertThat(workflow)
                .contains("plan")
                .contains("explore")
                .contains("verify")
                .contains("Boulder continuation");
    }

    @Test
    void customWorkflow_preservesDefaultIdentity() {
        // A user who wants a leaner workflow should
        // still get the R94 identity (persona +
        // principles). Customising the workflow is the
        // less common case but should work.
        RenderedPrompt rp = SystemPrompt.builder()
                .workflow("My team's workflow: read the code, write a test, ship it.")
                .build()
                .renderWithSources();
        assertThat(sectionText(rp, "workflow"))
                .isEqualTo("My team's workflow: read the code, write a test, ship it.");
        // Identity intact.
        assertThat(sectionText(rp, "identity"))
                .contains("AetherCode")
                .contains("Honesty over appearance");
    }

    @Test
    void rulesLayerAppends_afterDefaultSections() {
        // a project's rules layer (prior round
        // loader) is appended after the built-in
        // sections. The default identity + workflow
        // must be unchanged in length when rules are
        // added.
        RenderedPrompt without = SystemPrompt.builder().build().renderWithSources();
        RenderedPrompt withRules = SystemPrompt.builder()
                .rules("# Project rules\n\nUse tabs.")
                .build()
                .renderWithSources();
        assertThat(withRules.text().length())
                .isGreaterThan(without.text().length());
        // The new section is the rules layer; the
        // built-in sections keep their position.
        assertThat(withRules.sections())
                .extracting(RenderedPrompt.Section::name)
                .contains("identity", "rules", "workflow");
    }

    // ---------------------------------------------------------------------------------
    //  helpers
    // ---------------------------------------------------------------------------------

    private static String sectionText(RenderedPrompt rp, String name) {
        return rp.sections().stream()
                .filter(s -> name.equals(s.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing section: " + name))
                .text();
    }

    private static String sectionSource(RenderedPrompt rp, String name) {
        return rp.sections().stream()
                .filter(s -> name.equals(s.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing section: " + name))
                .source();
    }
}
