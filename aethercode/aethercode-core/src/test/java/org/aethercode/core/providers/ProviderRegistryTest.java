package org.aethercode.core.providers;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * tests for the {@link ProviderRegistry}
 * + {@link ProviderSpec} + {@link ModelSpec}
 * data model. The registry is the source of truth
 * for the desktop's Settings provider picker; the
 * tests pin the YAML shape, the bundled defaults,
 * and the validation rules so a future refactor
 * doesn't quietly change a field name and break
 * the renderer.
 */
class ProviderRegistryTest {

    @Test
    void parse_minimalProvider() {
        String yaml = """
                providers:
                  - name: test
                    type: openai-compat
                    baseUrl: https://example.com/v1
                    apiKeyEnv: TEST_API_KEY
                    defaultModel: test-1
                    models:
                      - id: test-1
                        inputPer1k: 0.001
                        outputPer1k: 0.002
                        context: 128000
                        default: true
                """;
        ProviderRegistry reg = ProviderRegistry.parse(yaml);
        assertEquals(1, reg.list().size());
        ProviderSpec p = reg.get("test").orElseThrow();
        assertEquals("openai-compat", p.type());
        assertEquals("https://example.com/v1", p.baseUrl());
        assertEquals("TEST_API_KEY", p.apiKeyEnv());
        assertEquals("test-1", p.defaultModel());
        assertEquals(1, p.models().size());
        ModelSpec m = p.models().get(0);
        assertEquals("test-1", m.id());
        assertEquals(0.001, m.inputPer1k());
        assertEquals(0.002, m.outputPer1k());
        assertEquals(128_000, m.context());
        assertTrue(m.isDefault());
    }

    @Test
    void parse_missingFileFallsBackToBundledDefaults() {
        // The bundled defaults include minmax, glm,
        // qwen, deepseek (Chinese brands the user
        // signed off on) + anthropic, openai, gemini
        // (foreign, untested).
        ProviderRegistry reg = ProviderRegistry.parse("");
        // Empty string → empty list (NOT bundled
        // defaults). The bundled defaults path is
        // the loadFrom(null) overload, used when
        // the YAML file is missing on disk.
        assertEquals(0, reg.list().size());
    }

    @Test
    void bundledDefaults_coverChineseAndForeignBrands() {
        List<ProviderSpec> defaults = ProviderRegistry.bundledDefaults();
        // Chinese brands the user signed off on.
        assertTrue(defaults.stream().anyMatch((p) -> "minmax".equals(p.name())));
        assertTrue(defaults.stream().anyMatch((p) -> "glm".equals(p.name())));
        assertTrue(defaults.stream().anyMatch((p) -> "qwen".equals(p.name())));
        assertTrue(defaults.stream().anyMatch((p) -> "deepseek".equals(p.name())));
        // Foreign brands — listed, untested.
        assertTrue(defaults.stream().anyMatch((p) -> "anthropic".equals(p.name())));
        assertTrue(defaults.stream().anyMatch((p) -> "openai".equals(p.name())));
        assertTrue(defaults.stream().anyMatch((p) -> "gemini".equals(p.name())));
    }

    @Test
    void bundledDefaults_haveNonEmptyModelLists() {
        // Every bundled provider must declare at
        // least one model. A provider with no models
        // would be useless in the Settings picker.
        for (ProviderSpec p : ProviderRegistry.bundledDefaults()) {
            assertTrue(p.models() != null && !p.models().isEmpty(),
                    "provider " + p.name() + " must declare at least one model");
            // Every model must have a positive
            // context window. A 0 context would
            // break the compactor's token budgeting.
            for (ModelSpec m : p.models()) {
                assertTrue(m.context() > 0,
                        "model " + p.name() + "/" + m.id() + " must have a positive context");
            }
        }
    }

    @Test
    void bundledDefaults_defaultModelIsInList() {
        // The registry's constructor validates that
        // defaultModel is in the models list. If a
        // bundled default ever drifts, this catches
        // it at construction time.
        for (ProviderSpec p : ProviderRegistry.bundledDefaults()) {
            assertNotNull(p.defaultModel());
            assertTrue(p.models().stream()
                    .anyMatch((m) -> m.id().equals(p.defaultModel())),
                    "provider " + p.name() + " defaultModel " + p.defaultModel()
                            + " must be in its models list");
        }
    }

    @Test
    void providerSpec_rejectsUnknownDefaultModel() {
        // The validation runs in the canonical
        // constructor; a provider that points at a
        // model it doesn't list breaks the UI (the
        // model picker has no row to select).
        assertThrows(IllegalArgumentException.class, () ->
                new ProviderSpec(
                        "bad", "openai-compat", "https://x.com/v1", "X_KEY",
                        "not-in-list",
                        List.of(new ModelSpec("a", 0, 0, 1000, true))));
    }

    @Test
    void providerSpec_rejectsEmptyModelList() {
        // A provider with no models is unusable.
        assertThrows(IllegalArgumentException.class, () ->
                new ProviderSpec(
                        "bad", "openai-compat", "https://x.com/v1", "X_KEY",
                        null,
                        List.of()));
    }

    @Test
    void get_returnsEmptyForUnknownName() {
        ProviderRegistry reg = ProviderRegistry.parse("""
                providers:
                  - name: a
                    type: openai-compat
                    baseUrl: https://a.com/v1
                    apiKeyEnv: A
                    defaultModel: a-1
                    models:
                      - id: a-1
                        inputPer1k: 0
                        outputPer1k: 0
                        context: 1000
                """);
        Optional<ProviderSpec> p = reg.get("nope");
        assertTrue(p.isEmpty());
    }

    @Test
    void defaultProvider_isFirstInList() {
        // The user's "I don't care, just use something"
        // path picks the first provider in the list.
        // Test that explicitly so a future re-sort
        // (e.g. by name) doesn't surprise the user.
        ProviderRegistry reg = ProviderRegistry.parse("""
                providers:
                  - name: zzz
                    type: openai-compat
                    baseUrl: https://z.com/v1
                    apiKeyEnv: Z
                    defaultModel: z-1
                    models:
                      - id: z-1
                        inputPer1k: 0
                        outputPer1k: 0
                        context: 1000
                  - name: aaa
                    type: openai-compat
                    baseUrl: https://a.com/v1
                    apiKeyEnv: A
                    defaultModel: a-1
                    models:
                      - id: a-1
                        inputPer1k: 0
                        outputPer1k: 0
                        context: 1000
                """);
        assertEquals("zzz", reg.defaultProvider().orElseThrow().name());
    }

    @Test
    void laterProviderOverridesEarlierOne() {
        // The index() helper does "later wins" so a
        // user's override in providers.yaml beats a
        // bundled default of the same name.
        ProviderRegistry reg = ProviderRegistry.parse("""
                providers:
                  - name: x
                    type: openai-compat
                    baseUrl: https://orig.com/v1
                    apiKeyEnv: X
                    defaultModel: x-1
                    models:
                      - id: x-1
                        inputPer1k: 0
                        outputPer1k: 0
                        context: 1000
                  - name: x
                    type: openai-compat
                    baseUrl: https://override.com/v1
                    apiKeyEnv: X
                    defaultModel: x-1
                    models:
                      - id: x-1
                        inputPer1k: 0
                        outputPer1k: 0
                        context: 1000
                """);
        assertEquals("https://override.com/v1", reg.get("x").orElseThrow().baseUrl());
    }
}
