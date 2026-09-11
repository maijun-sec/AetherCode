package org.aethercode.prompts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * tests for the design-first prompt section. Validates default
 * content, builder override, and ordering (design-first must appear
 * BEFORE workflow in the rendered prompt).
 */
class DesignFirstPromptTest {

    @Test
    void defaultDesignFirst_isNonEmpty_andMentionsRoundTemplate() {
        String df = SystemPrompt.builder().build().render();
        // The default section is auto-included.
        assertThat(df).contains("Design first, then code");
        assertThat(df).contains("Rounds.");
        assertThat(df).contains("no confirmation needed for next N rounds");
    }

    @Test
    void designFirst_isRenderedBeforeWorkflow() {
        String rendered = SystemPrompt.builder()
                .identity("IDENTITY-SENTINEL")
                .workflow("WORKFLOW-SENTINEL")
                .build()
                .render();
        int df = rendered.indexOf("Design first, then code");
        int wf = rendered.indexOf("WORKFLOW-SENTINEL");
        assertThat(df).isGreaterThanOrEqualTo(0);
        assertThat(wf).isGreaterThan(df);
    }

    @Test
    void designFirst_emptyString_disablesSection() {
        String rendered = SystemPrompt.builder()
                .designFirst("")
                .build()
                .render();
        // The default design-first block is gone.
        assertThat(rendered).doesNotContain("Design first, then code");
        // ...but the rest of the prompt is still there.
        assertThat(rendered).contains("AetherCode");
    }

    @Test
    void designFirst_nullClearsSection() {
        String rendered = SystemPrompt.builder()
                .designFirst("custom-design-block")
                .designFirst(null)
                .build()
                .render();
        // null clears the section (treats it like empty string).
        assertThat(rendered).doesNotContain("custom-design-block");
        // The default design-first block is also gone (since the user
        // explicitly cleared it). The rest of the prompt is intact.
        assertThat(rendered).contains("AetherCode");
    }

    @Test
    void designFirst_customString_isRendered() {
        String rendered = SystemPrompt.builder()
                .designFirst("MY-CUSTOM-DESIGN-FIRST-SENTINEL")
                .build()
                .render();
        assertThat(rendered).contains("MY-CUSTOM-DESIGN-FIRST-SENTINEL");
    }

    @Test
    void renderWithSources_includesDesignFirstSection() {
        RenderedPrompt rp = SystemPrompt.builder().build().renderWithSources();
        // Find a section named "designFirst" with the default content.
        boolean foundDf = rp.sections().stream()
                .anyMatch(s -> "designFirst".equals(s.name())
                        && s.text().contains("Design first, then code"));
        assertThat(foundDf).isTrue();
    }

    @Test
    void designFirst_includedInStructuredSectionsBeforeWorkflow() {
        RenderedPrompt rp = SystemPrompt.builder().build().renderWithSources();
        int dfIdx = -1, wfIdx = -1;
        for (int i = 0; i < rp.sections().size(); i++) {
            String n = rp.sections().get(i).name();
            if ("designFirst".equals(n)) dfIdx = i;
            if ("workflow".equals(n)) wfIdx = i;
        }
        assertThat(dfIdx).isGreaterThanOrEqualTo(0);
        assertThat(wfIdx).isGreaterThan(dfIdx);
    }
}
