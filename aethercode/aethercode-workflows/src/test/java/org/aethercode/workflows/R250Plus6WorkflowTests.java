package org.aethercode.workflows;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for the {@code code-review} and {@code migrate-deps} workflows.
 * These pin the contracts the agent loop depends on (input schema, output schema,
 * declared skills); if any of them is broken by a later change, the test fails here
 * before the regression ships.
 *
 * <p>The tests use {@link WorkflowLoader} to parse the published YAML resources and
 * inspect the {@link Workflow} record directly, rather than actually running the
 * workflows (no chat model required). Engine-layer integration is covered by the
 * sibling {@code WorkflowEngineTest}.</p>
 */
class R250Plus6WorkflowTests {

    @Test
    void codeReviewDeclaresAllExpectedInputs() {
        Workflow wf = load("code-review");
        // The review workflow accepts target / diff / base / focus / max-findings.
        // The user-facing schema IS the contract — if any of these is missing
        // the model would invoke the workflow with a malformed call.
        Set<String> inputNames = wf.inputs().keySet();
        assertThat(inputNames)
                .contains("target", "diff", "base-branch", "focus", "max-findings");
    }

    @Test
    void codeReviewDeclaresVerdictOutput() {
        Workflow wf = load("code-review");
        // The review workflow's terminal state is a verdict enum
        // (APPROVE / REQUEST-CHANGES / NEEDS-DISCUSSION); the supervisor uses
        // it to decide whether to merge without re-reading the report.
        // The current version doesn't expose outputs on the Workflow record
        // accessor, so we read the raw YAML map directly.
        assertThat(wf.raw()).containsKey("outputs");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> outputs =
                (java.util.Map<String, Object>) wf.raw().get("outputs");
        assertThat(outputs).containsKey("verdict");
        assertThat(outputs).containsKey("findings");
    }

    @Test
    void codeReviewDeclaresRequiredSkills() {
        Workflow wf = load("code-review");
        // The agent pulls these skills into the system prompt while running
        // a review; missing one noticeably drops the review's quality.
        assertThat(wf.skills())
                .contains("code-review", "security-audit-basics");
    }

    @Test
    void migrateDepsDeclaresScopeAndExclusionInputs() {
        Workflow wf = load("migrate-deps");
        Set<String> inputNames = wf.inputs().keySet();
        assertThat(inputNames)
                .contains("manifest", "scope", "exclude", "allow-major", "dry-run");
    }

    @Test
    void migrateDepsDryRunDefaultsToTrue() {
        Workflow wf = load("migrate-deps");
        // The default for dry-run is critical: a workflow
        // that mutates pom.xml on first run would be a
        // landmine. We assert the default explicitly so a
        // future refactor that drops the default fails
        // this test, not a user's project.
        Workflow.InputDef dryRun = wf.inputs().get("dry-run");
        assertThat(dryRun).isNotNull();
        // The InputDef stores defaultValue as Object; for
        // a boolean the runtime value is `true`, not the
        // string "true". Cast accordingly.
        assertThat(dryRun.defaultValue()).isEqualTo(true);
    }

    @Test
    void migrateDepsDeclaresSemverSkill() {
        // The semver skill is what makes scope=patch /
        // minor / major actually mean what they say.
        // Without it the agent would propose a major
        // bump when the user asked for patch-only.
        Workflow wf = load("migrate-deps");
        assertThat(wf.skills()).contains("semver", "maven", "gradle");
    }

    @Test
    void migrateDepsOutputCapturesUpgradeList() {
        Workflow wf = load("migrate-deps");
        // The output schema needs an upgrade-list item
        // so the supervisor can write a release note
        // from the run.
        assertThat(wf.raw()).containsKey("outputs");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> outputs =
                (java.util.Map<String, Object>) wf.raw().get("outputs");
        assertThat(outputs).containsKey("upgrade-list");
    }

    @Test
    void allR250Plus6WorkflowsValidateCleanly() {
        WorkflowValidator v = new WorkflowValidator();
        for (String name : List.of("code-review", "migrate-deps")) {
            Workflow wf = load(name);
            assertThat(v.validate(wf))
                    .as("validator finds no problems in shipped %s", name)
                    .isEmpty();
        }
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    private static Workflow load(String name) {
        return new WorkflowLoader()
                .loadResource("workflows/" + name + ".yaml");
    }
}
