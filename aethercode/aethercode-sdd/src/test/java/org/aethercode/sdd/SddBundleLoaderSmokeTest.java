package org.aethercode.sdd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R700 — Verifies {@link SddBundleLoader#load()} against the actual jar
 * layout (this test runs as part of the module test phase, so the
 * {@code skills/sdd/*} resources are guaranteed on the classpath).
 *
 * <p>If a future refactor renames a file under
 * {@code src/main/resources/skills/sdd/} this test catches it.
 */
class SddBundleLoaderSmokeTest {

    @Test
    void load_reads_skill_md_and_all_eight_phase_refs() {
        SddBundleLoader bundle = SddBundleLoader.load();
        assertThat(bundle.isLoaded()).isTrue();
        assertThat(bundle.skillBody()).isNotBlank();

        for (int n = 1; n <= SddPhaseSpec.TOTAL_PHASES; n++) {
            String body = bundle.phaseReference(n);
            assertThat(body)
                    .as("phase %d reference", n)
                    .isNotBlank()
                    .containsIgnoringCase("phase " + n);
        }
    }

    @Test
    void load_includes_protocol_and_credits() {
        SddBundleLoader bundle = SddBundleLoader.load();
        assertThat(bundle.phaseProtocol()).isNotBlank();
        assertThat(bundle.upstreamCredits()).containsIgnoringCase("spec-kit");
    }

    @Test
    void load_supports_each_template_base_name() {
        SddBundleLoader bundle = SddBundleLoader.load();
        // Each of the four expected templates should resolve.
        for (String name : new String[]{"constitution-template",
                                         "specify-template",
                                         "plan-template",
                                         "tasks-template"}) {
            assertThat(bundle.template(name))
                    .as("template %s", name)
                    .isNotBlank();
        }
    }

    @Test
    void phaseReference_rejects_invalid_number() {
        SddBundleLoader bundle = SddBundleLoader.load();
        assertThatThrownBy(() -> bundle.phaseReference(99))
                .isInstanceOf(IllegalArgumentException.class);
    }
}