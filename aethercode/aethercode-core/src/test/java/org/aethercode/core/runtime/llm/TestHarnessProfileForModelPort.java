package org.aethercode.core.runtime.llm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 1:1 port of {@code test_graph.py::TestProfileForModel}.
 *
 * <p>Exercises the Java port's
 * {@link HarnessProfile#harnessProfileForModel(Object, String)}
 * resolver: the {@code spec} argument is consulted first; if absent,
 * the resolved identifier + provider of the pre-built model is used
 * to look up a profile; missing matches fall back to a default
 * empty profile.</p>
 */
class TestHarnessProfileForModelPort {

    private Map<String, HarnessProfile> saved;

    @BeforeEach
    void saveRegistry() {
        // Snapshot the current registry so we can restore it after
        // each test. TestProfileForModel uses the same pattern in
        // Python.
        saved = HarnessProfile.snapshot();
    }

    @AfterEach
    void restoreRegistry() {
        HarnessProfile.restoreSnapshot(saved);
    }

    private static class FakeModel {
        final String model_name;
        final String ls_provider;
        FakeModel(String model_name, String lsProvider) {
            this.model_name = model_name;
            this.ls_provider = lsProvider;
        }
        public String model_name() { return model_name; }
        @SuppressWarnings("unused")
        public java.util.Map<String, String> _get_ls_params() {
            return java.util.Map.of("ls_provider", ls_provider);
        }
    }

    @Test
    @DisplayName("harnessProfileForModel returns the spec-keyed profile when spec is provided")
    void usesSpecWhenProvided() {
        // Mirrors Python's `test_uses_spec_when_provided`:
        // a profile registered under "testprov:some-model" is
        // returned when the spec argument matches exactly.
        HarnessProfile p = new HarnessProfile(
                null, "from spec", Map.of(), Set.of(), Set.of(), List.of(), null);
        HarnessProfile.registerHarnessProfile("testprov:some-model", p);
        FakeModel model = new FakeModel("any", "any");
        HarnessProfile result = HarnessProfile.harnessProfileForModel(model, "testprov:some-model");
        assertThat(result).isSameAs(p);
    }

    @Test
    @DisplayName("harnessProfileForModel returns empty default when spec is unknown")
    void returnsEmptyDefaultWhenSpecUnknown() {
        // No profile registered under "unknown:spec".
        FakeModel model = new FakeModel("any", "any");
        HarnessProfile result = HarnessProfile.harnessProfileForModel(model, "unknown:spec");
        // Default is empty: no suffix, no extras, etc.
        assertThat(result.systemPromptSuffix()).isNull();
        assertThat(result.excludedMiddleware()).isEmpty();
    }

    @Test
    @DisplayName("harnessProfileForModel falls back to provider + identifier when spec is null")
    void fallsBackToProviderPlusIdentifier() {
        // Mirrors Python's
        // `test_matches_combined_provider_model_key_for_prebuilt`:
        // a pre-built model with provider="fakeprov" and
        // identifier="my-model" should match a profile registered
        // under "fakeprov:my-model".
        HarnessProfile model_profile = new HarnessProfile(
                null, "model level", Map.of(), Set.of(), Set.of(), List.of(), null);
        HarnessProfile.registerHarnessProfile("fakeprov:my-model", model_profile);
        FakeModel model = new FakeModel("my-model", "fakeprov");
        HarnessProfile result = HarnessProfile.harnessProfileForModel(model, null);
        assertThat(result).isSameAs(model_profile);
    }

    @Test
    @DisplayName("harnessProfileForModel returns default when nothing matches")
    void returnsEmptyDefaultWhenNoMatch() {
        // No profiles registered, no spec. The resolver returns
        // a default empty HarnessProfile.
        FakeModel model = new FakeModel("unknown", "unknown");
        HarnessProfile result = HarnessProfile.harnessProfileForModel(model, null);
        assertThat(result.systemPromptSuffix()).isNull();
        assertThat(result.excludedMiddleware()).isEmpty();
    }
}
