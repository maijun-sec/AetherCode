package org.aethercode.prompts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tests for {@link SystemPrompt#renderWithSources()} and
 * the {@link RenderedPrompt} record it returns. The
 * {@code text} field is the same string {@code render()} would
 * have produced; the {@code sections} list is the new
 * provenance channel. These tests lock both halves of that
 * contract.
 */
class RenderedPromptTest {

    @Test
    void renderWithSources_textMatchesRender() {
        // The text produced by renderWithSources() must be
        // byte-identical to the legacy render() string.
        SystemPrompt p = SystemPrompt.builder()
                .identity("ID")
                .environment("ENV")
                .build();
        assertThat(p.renderWithSources().text()).isEqualTo(p.render());
    }

    @Test
    void renderWithSources_listsOnlyNonEmptySections() {
        // A prompt with no rules, no planMode, no memory
        // should produce a 4-section list: identity,
        // environment, designFirst, workflow. The other slots
        // are absent because their builder fields are blank
        // / null. R98 added the designFirst section.
        RenderedPrompt rp = SystemPrompt.builder()
                .identity("ID")
                .environment("ENV")
                .build()
                .renderWithSources();
        assertThat(rp.sections())
                .extracting(RenderedPrompt.Section::name)
                .containsExactly("identity", "environment", "designFirst", "workflow");
    }

    @Test
    void renderWithSources_sectionOrderMatchesRender() {
        // Section order in the list must match the
        // concatenation order in the text. R98 added the
        // designFirst slot between tooling and workflow.
        RenderedPrompt rp = SystemPrompt.builder()
                .identity("ID")
                .rules("R")
                .environment("ENV")
                .tooling("T")
                .workflow("W")
                .planMode("PM")
                .memory("M")
                .build()
                .renderWithSources();
        assertThat(rp.sections())
                .extracting(RenderedPrompt.Section::name)
                .containsExactly("identity", "rules", "environment",
                        "tooling", "designFirst", "workflow", "planMode", "memory");
    }

    @Test
    void renderWithSources_defaultIdentityTaggedAsDefault() {
        RenderedPrompt.Section identity = SystemPrompt.builder()
                .build()  // identity not overridden
                .renderWithSources()
                .sections().stream()
                .filter(s -> "identity".equals(s.name()))
                .findFirst().orElseThrow();
        assertThat(identity.source()).isEqualTo("default");
        assertThat(identity.text()).startsWith("You are AetherCode");
    }

    @Test
    void renderWithSources_customIdentityTaggedAsBuilder() {
        RenderedPrompt.Section identity = SystemPrompt.builder()
                .identity("custom agent")
                .build()
                .renderWithSources()
                .sections().stream()
                .filter(s -> "identity".equals(s.name()))
                .findFirst().orElseThrow();
        assertThat(identity.source()).isEqualTo("builder");
    }

    @Test
    void renderWithSources_rulesSourceFromContent() {
        // The "rules:project+global" label is set when the
        // concatenated content contains the canonical
        // header that RulesLoader writes. A builder-only
        // call (no header) gets the "rules:builder" label.
        RenderedPrompt.Section r1 = SystemPrompt.builder()
                .rules("# Project rules\n\nfoo")
                .build()
                .renderWithSources()
                .sections().stream()
                .filter(s -> "rules".equals(s.name()))
                .findFirst().orElseThrow();
        assertThat(r1.source()).isEqualTo("rules:project+global");
    }

    @Test
    void renderWithSources_blankRules_omitsSection() {
        // A blank rules value should be omitted from the
        // sections list (the text also omits it). The
        // legacy behaviour for render() is preserved.
        RenderedPrompt rp = SystemPrompt.builder()
                .rules("   \n  \n  ")
                .build()
                .renderWithSources();
        assertThat(rp.sections())
                .extracting(RenderedPrompt.Section::name)
                .doesNotContain("rules");
    }

    @Test
    void renderWithSources_planModeAndMemoryTaggedDistinctly() {
        RenderedPrompt rp = SystemPrompt.builder()
                .planMode("PM-BODY")
                .memory("M-BODY")
                .build()
                .renderWithSources();
        assertThat(sectionSource(rp, "planMode")).isEqualTo("plan-mode");
        assertThat(sectionSource(rp, "memory")).isEqualTo("memory");
    }

    @Test
    void renderWithSources_defaultWorkflowTaggedAsDefault() {
        RenderedPrompt.Section wf = SystemPrompt.builder()
                .build()
                .renderWithSources()
                .sections().stream()
                .filter(s -> "workflow".equals(s.name()))
                .findFirst().orElseThrow();
        assertThat(wf.source()).isEqualTo("default");
    }

    @Test
    void summary_isHumanReadable() {
        // The summary() helper on RenderedPrompt should
        // produce one line per section with a stable
        // shape: [name] source=X length=N first="...".
        String sum = SystemPrompt.builder()
                .identity("My custom agent")
                .environment("cwd=/tmp")
                .build()
                .renderWithSources()
                .summary();
        assertThat(sum).contains("[identity]").contains("source=builder");
        assertThat(sum).contains("[environment]");
        assertThat(sum).contains("length=");
        assertThat(sum).contains("first=\"My custom agent\"");
    }

    @Test
    void sectionRecord_normalizesNulls() {
        // The Section record's compact constructor should
        // normalize nulls so callers don't have to
        // null-check before rendering.
        RenderedPrompt.Section s = new RenderedPrompt.Section(null, null, null);
        assertThat(s.name()).isEqualTo("(unnamed)");
        assertThat(s.text()).isEmpty();
        assertThat(s.source()).isEqualTo("unknown");
    }

    private static String sectionSource(RenderedPrompt rp, String name) {
        return rp.sections().stream()
                .filter(s -> name.equals(s.name()))
                .findFirst()
                .orElseThrow()
                .source();
    }
}
