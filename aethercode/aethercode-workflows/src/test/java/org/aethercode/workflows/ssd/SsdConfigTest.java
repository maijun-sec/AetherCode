package org.aethercode.workflows.ssd;

import org.aethercode.workflows.ssd.SsdConfig.Phase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R236 — exercises the SsdConfig loader, validator, and template
 * substitution. Does NOT touch the LLM or the filesystem beyond
 * the {@link TempDir} for the project-override test.
 */
class SsdConfigTest {

    @Test
    void bundledConfigHasAllFourPhases() {
        SsdConfig cfg = SsdConfig.fromBundled();
        assertThat(cfg.source()).isEqualTo(SsdConfig.Source.BUNDLED);
        assertThat(cfg.name()).isEqualTo("ssd");
        assertThat(cfg.artefactRoot()).isEqualTo(".aethercode/ssd");
        // Phases in order: spec, design, tasks, dev (orders 1..4)
        List<Phase> ordered = cfg.orderedPhases();
        assertThat(ordered).hasSize(4);
        assertThat(ordered.get(0).id()).isEqualTo("spec");
        assertThat(ordered.get(0).order()).isEqualTo(1);
        assertThat(ordered.get(1).id()).isEqualTo("design");
        assertThat(ordered.get(1).order()).isEqualTo(2);
        assertThat(ordered.get(2).id()).isEqualTo("tasks");
        assertThat(ordered.get(2).order()).isEqualTo(3);
        assertThat(ordered.get(3).id()).isEqualTo("dev");
        assertThat(ordered.get(3).order()).isEqualTo(4);
    }

    @Test
    void bundledConfigHasNoEmptyPrompts() {
        SsdConfig cfg = SsdConfig.fromBundled();
        for (Phase p : cfg.phases()) {
            assertThat(p.systemPrompt())
                    .as("phase %s systemPrompt", p.id())
                    .isNotBlank();
            assertThat(p.userPromptTemplate())
                    .as("phase %s userPromptTemplate", p.id())
                    .isNotBlank();
            assertThat(p.userRevisionTemplate())
                    .as("phase %s userRevisionTemplate", p.id())
                    .isNotBlank();
            assertThat(p.maxTokens())
                    .as("phase %s maxTokens", p.id())
                    .isGreaterThan(0);
        }
    }

    @Test
    void bundledHardRulesContainsKeyConstraints() {
        String rules = SsdConfig.fromBundled().hardRules();
        // The hard rules are the most important behaviour: they
        // keep the model from running tools mid-phase. Make sure
        // the constraints R235 discovered are still present.
        assertThat(rules).contains("Do NOT call any tools");
        assertThat(rules).contains("<think>...</think>");
        assertThat(rules).contains("Output ONLY the markdown");
    }

    @Test
    void phaseByIdLooksUpTheBundledNames() {
        SsdConfig cfg = SsdConfig.fromBundled();
        assertThat(cfg.phaseById("spec")).isPresent();
        assertThat(cfg.phaseById("design")).isPresent();
        assertThat(cfg.phaseById("tasks")).isPresent();
        assertThat(cfg.phaseById("dev")).isPresent();
        assertThat(cfg.phaseById("nonexistent")).isEmpty();
        assertThat(cfg.phaseById(null)).isEmpty();
    }

    @Test
    void templateSubstitutionFillsKnownVars() {
        SsdConfig cfg = SsdConfig.fromBundled();
        Phase spec = cfg.phaseById("spec").orElseThrow();
        String rendered = spec.renderUserPrompt(Map.of(
                "feature", "add-sidenote",
                "intent", "add a SideNote event to the stream bus"));
        assertThat(rendered).contains("add-sidenote");
        assertThat(rendered).contains("add a SideNote event to the stream bus");
        assertThat(rendered).contains("Phase 1 of 4");
    }

    @Test
    void templateSubstitutionIgnoresUnknownVars() {
        // A {{nonexistent}} in the template must not blow up; the
        // loader leaves a missing variable blank (not literal) so
        // a partial bag doesn't crash the runner. This is the
        // soft-fail contract R235 settled on.
        SsdConfig cfg = SsdConfig.fromBundled();
        Phase spec = cfg.phaseById("spec").orElseThrow();
        String rendered = spec.renderUserPrompt(Map.of("feature", "x"));
        assertThat(rendered).doesNotContain("{{feature}}");
        assertThat(rendered).doesNotContain("{{nonexistent}}");
    }

    @Test
    void projectOverrideTakesPrecedenceOverBundled(@TempDir Path tmp) throws IOException {
        Path override = tmp.resolve(".aethercode").resolve("ssd");
        Files.createDirectories(override);
        Files.writeString(override.resolve("ssd.yaml"), """
                version: 1
                name: ssd-test-override
                description: test override
                phases:
                  - id: spec
                    order: 1
                    file: spec.md
                    title: Spec
                    userPromptTemplate: "OVERRIDE {{feature}}"
                    userRevisionTemplate: "OVERRIDE-REV {{feature}}"
                    systemPrompt: OVERRIDE-SYS
                    maxTokens: 1024
                  - id: design
                    order: 2
                    file: design.md
                    title: Design
                    userPromptTemplate: DESIGN
                    userRevisionTemplate: DESIGN-REV
                    systemPrompt: DESIGN-SYS
                    maxTokens: 1024
                  - id: tasks
                    order: 3
                    file: tasks.md
                    title: Tasks
                    userPromptTemplate: TASKS
                    userRevisionTemplate: TASKS-REV
                    systemPrompt: TASKS-SYS
                    maxTokens: 1024
                  - id: dev
                    order: 4
                    file: dev.log
                    title: Dev
                    userPromptTemplate: DEV
                    userRevisionTemplate: DEV-REV
                    systemPrompt: DEV-SYS
                    maxTokens: 1024
                """, StandardCharsets.UTF_8);
        SsdConfig cfg = SsdConfig.fromProjectOrBundled(tmp);
        assertThat(cfg.source()).isEqualTo(SsdConfig.Source.PROJECT);
        assertThat(cfg.name()).isEqualTo("ssd-test-override");
        Phase spec = cfg.phaseById("spec").orElseThrow();
        assertThat(spec.systemPrompt()).isEqualTo("OVERRIDE-SYS");
        assertThat(spec.renderUserPrompt(Map.of("feature", "f"))).isEqualTo("OVERRIDE f");
    }

    @Test
    void missingRequiredPhaseFails(@TempDir Path tmp) throws IOException {
        Path override = tmp.resolve(".aethercode").resolve("ssd");
        Files.createDirectories(override);
        Files.writeString(override.resolve("ssd.yaml"), """
                version: 1
                name: bad
                phases:
                  - id: spec
                    order: 1
                    file: spec.md
                    title: Spec
                    userPromptTemplate: SPEC
                    userRevisionTemplate: SPEC-REV
                    systemPrompt: SPEC-SYS
                    maxTokens: 1024
                """, StandardCharsets.UTF_8);
        // project file is malformed (missing design/tasks/dev) —
        // loader falls back to bundled, which is what the contract
        // promises: "if the override is broken, you still get a
        // working SSD".
        SsdConfig cfg = SsdConfig.fromProjectOrBundled(tmp);
        assertThat(cfg.source()).isEqualTo(SsdConfig.Source.BUNDLED);
    }

    @Test
    void inlineConfigMissingRequiredPhaseFailsLoud() {
        // Direct parse() (test path) does NOT fall back — tests
        // that feed a bad YAML should see the validation error.
        String bad = """
                version: 1
                name: bad
                phases:
                  - id: spec
                    order: 1
                    file: spec.md
                    title: Spec
                    userPromptTemplate: SPEC
                    userRevisionTemplate: SPEC-REV
                    systemPrompt: SPEC-SYS
                    maxTokens: 1024
                """;
        assertThatThrownBy(() -> SsdConfig.parse(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("design");
    }

    @Test
    void orderedPhasesSortsByOrderField() {
        String yaml = """
                version: 1
                name: shuffled
                phases:
                  - id: tasks
                    order: 3
                    file: tasks.md
                    title: Tasks
                    userPromptTemplate: T
                    userRevisionTemplate: TR
                    systemPrompt: TS
                    maxTokens: 1024
                  - id: spec
                    order: 1
                    file: spec.md
                    title: Spec
                    userPromptTemplate: S
                    userRevisionTemplate: SR
                    systemPrompt: SS
                    maxTokens: 1024
                  - id: design
                    order: 2
                    file: design.md
                    title: Design
                    userPromptTemplate: D
                    userRevisionTemplate: DR
                    systemPrompt: DS
                    maxTokens: 1024
                  - id: dev
                    order: 4
                    file: dev.log
                    title: Dev
                    userPromptTemplate: V
                    userRevisionTemplate: VR
                    systemPrompt: VS
                    maxTokens: 1024
                """;
        SsdConfig cfg = SsdConfig.parse(yaml);
        List<Phase> ordered = cfg.orderedPhases();
        assertThat(ordered.get(0).id()).isEqualTo("spec");
        assertThat(ordered.get(1).id()).isEqualTo("design");
        assertThat(ordered.get(2).id()).isEqualTo("tasks");
        assertThat(ordered.get(3).id()).isEqualTo("dev");
    }

    @Test
    void substituteHandlesEmptyTemplate() {
        assertThat(SsdConfig.substitute("", Map.of("x", "y"))).isEmpty();
        assertThat(SsdConfig.substitute(null, Map.of("x", "y"))).isEmpty();
    }

    @Test
    void substituteLeavesUnterminatedTagAlone() {
        // "{{feature" with no closing }} should not throw — the
        // runner renders templates for every phase, and a typo
        // in the YAML must not abort the whole SSD.
        String t = "hello {{feature world";
        String r = SsdConfig.substitute(t, Map.of("feature", "X"));
        assertThat(r).isEqualTo("hello {{feature world");
    }
}
