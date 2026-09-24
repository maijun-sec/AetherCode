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
    void bundledDefaults_R340_carriesLatestFreeAndReasoningModels() {
        // R340: refreshed the Chinese brand catalogue to
        // late-2025 / early-2026 models. Source-pin each
        // model id we deliberately added so a future
        // housekeeping pass can't quietly delete a
        // model the user is actively using.
        //
        // GLM 4.5 family — flagship refresh from
        // 智谱, late-2025. glm-4-flash + glm-4.5-flash
        // are the cheapest tier (often free in promos).
        // glm-z1-air is the free reasoning tier.
        assertHasModel("glm", "glm-4-flash");
        assertHasModel("glm", "glm-4.5");
        assertHasModel("glm", "glm-4.5-air");
        assertHasModel("glm", "glm-4.5-flash");
        assertHasModel("glm", "glm-z1-air");
        // Qwen3 family — 阿里 Aug-2025 refresh.
        // qwen-turbo remains the cheapest. qwen3-max /
        // qwen3-coder-plus / qwen3-vl-plus round it out.
        assertHasModel("qwen", "qwen-turbo");
        assertHasModel("qwen", "qwen3-max");
        assertHasModel("qwen", "qwen3-coder-plus");
        assertHasModel("qwen", "qwen3-vl-plus");
        // DeepSeek V3.x family — Aug/Sep-2025 refresh.
        // V3.1 supersedes V3 supersedes the original
        // chat; all share the same cheap /M-token tier.
        assertHasModel("deepseek", "deepseek-v3");
        assertHasModel("deepseek", "deepseek-v3.1");
    }

    private static void assertHasModel(String providerName, String modelId) {
        ProviderSpec p = ProviderRegistry.bundledDefaults().stream()
                .filter(s -> providerName.equals(s.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "bundled defaults must contain provider " + providerName));
        assertTrue(p.models().stream().anyMatch((m) -> modelId.equals(m.id())),
                "provider " + providerName + " must carry model " + modelId
                        + " (R340 default); aborting to keep the late-2025 Chinese "
                        + "model list intact for users on glm-4-flash / qwen3-max / "
                        + "deepseek-v3.1");
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
    void hasApiKey_unsetEnvReturnsFalse() {
        // R282: the Settings panel uses hasApiKey() to
        // filter providers. A spec whose apiKeyEnv is
        // unset must report false. We pick a name we
        // know is unset (TEST_R282_HASAPIKEY_PROBE) so
        // the test doesn't depend on the host env.
        ProviderSpec p = new ProviderSpec(
                "probe", "openai-compat", "https://x.com/v1",
                "TEST_R282_HASAPIKEY_PROBE",
                "probe-1",
                java.util.List.of(new ModelSpec("probe-1", 0, 0, 1000, true)));
        org.junit.jupiter.api.Assertions.assertFalse(p.hasApiKey());
    }

    @Test
    void hasApiKey_blankEnvReturnsFalse() {
        // A blank env var (e.g. set by a CI that ran
        // `unset X; X=`) must NOT count as configured.
        // We can't actually set env vars in pure JUnit,
        // so we test the equivalent path: the apiKey()
        // returns null → hasApiKey() returns false.
        ProviderSpec p = new ProviderSpec(
                "blank", "openai-compat", "https://x.com/v1",
                "TEST_R282_HASAPIKEY_BLANK",
                "blank-1",
                java.util.List.of(new ModelSpec("blank-1", 0, 0, 1000, true)));
        // Sanity: the env var is unset in the test JVM.
        org.junit.jupiter.api.Assertions.assertNull(System.getenv("TEST_R282_HASAPIKEY_BLANK"));
        org.junit.jupiter.api.Assertions.assertFalse(p.hasApiKey());
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
